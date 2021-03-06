/**
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql.rest.server.computation;

import io.confluent.ksql.KsqlEngine;
import io.confluent.ksql.ddl.DdlConfig;
import io.confluent.ksql.ddl.commands.*;
import io.confluent.ksql.exception.ExceptionUtil;
import io.confluent.ksql.serde.DataSource;
import io.confluent.ksql.parser.tree.CreateStream;
import io.confluent.ksql.parser.tree.CreateStreamAsSelect;
import io.confluent.ksql.parser.tree.CreateTable;
import io.confluent.ksql.parser.tree.CreateTableAsSelect;
import io.confluent.ksql.parser.tree.RunScript;
import io.confluent.ksql.parser.tree.RegisterTopic;
import io.confluent.ksql.parser.tree.DropStream;
import io.confluent.ksql.parser.tree.DropTable;
import io.confluent.ksql.parser.tree.DropTopic;
import io.confluent.ksql.parser.tree.Query;
import io.confluent.ksql.parser.tree.QuerySpecification;
import io.confluent.ksql.parser.tree.Relation;
import io.confluent.ksql.parser.tree.Statement;
import io.confluent.ksql.parser.tree.Table;
import io.confluent.ksql.parser.tree.TerminateQuery;
import io.confluent.ksql.planner.plan.KsqlStructuredDataOutputNode;
import io.confluent.ksql.rest.entity.CommandStatus;
import io.confluent.ksql.rest.server.StatementParser;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.Pair;
import io.confluent.ksql.util.PersistentQueryMetadata;
import io.confluent.ksql.util.QueryMetadata;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the actual execution (or delegation to KSQL core) of all distributed statements, as well
 * as tracking their statuses as things move along.
 */
public class StatementExecutor {

  private static final Logger log = LoggerFactory.getLogger(StatementExecutor.class);

  private static final Pattern TERMINATE_PATTERN =
      Pattern.compile("\\s*TERMINATE\\s+([0-9]+)\\s*;?\\s*");

  private final KsqlEngine ksqlEngine;
  private final StatementParser statementParser;
  private final Map<CommandId, CommandStatus> statusStore;
  private final Map<CommandId, CommandStatusFuture> statusFutures;

  public StatementExecutor(
      KsqlEngine ksqlEngine,
      StatementParser statementParser
  ) {
    this.ksqlEngine = ksqlEngine;
    this.statementParser = statementParser;

    this.statusStore = new HashMap<>();
    this.statusFutures = new HashMap<>();
  }

  public void handleStatements(List<Pair<CommandId, Command>> priorCommands) throws Exception {
    for (Pair<CommandId, Command> commandIdCommandPair: priorCommands) {
      log.info("Executing prior statement: '{}'", commandIdCommandPair.getRight());
      try {
        handleStatementWithTerminatedQueries(
            commandIdCommandPair.getRight(),
            commandIdCommandPair.getLeft(),
            Collections.emptyMap()
        );
      } catch (Exception exception) {
        log.warn("Failed to execute statement due to exception", exception);
      }
    }
  }

  /**
   * Attempt to execute a single statement.
   * @param command The string containing the statement to be executed
   * @param commandId The ID to be used to track the status of the command
   * @throws Exception TODO: Refine this.
   */
  public void handleStatement(
      Command command,
      CommandId commandId
  ) throws Exception {
    handleStatementWithTerminatedQueries(command, commandId, null);
  }

  /**
   * Get details on the statuses of all the statements handled thus far.
   * @return A map detailing the current statuses of all statements that the handler has executed
   *         (or attempted to execute).
   */
  public Map<CommandId, CommandStatus> getStatuses() {
    return new HashMap<>(statusStore);
  }

  /**
   * @param statementId The ID of the statement to check the status of.
   * @return Information on the status of the statement with the given ID, if one exists.
   */
  public Optional<CommandStatus> getStatus(CommandId statementId) {
    return Optional.ofNullable(statusStore.get(statementId));
  }

  /**
   * Register the existence of a new statement that has been written to the command topic. All other
   * statement status information is updated exclusively by the current {@link StatementExecutor}
   * instance, but in the (unlikely but possible) event that a statement is written to the command
   * topic but never picked up by this instance, it should be possible to know that it was at least
   * written to the topic in the first place.
   * @param commandId The ID of the statement that has been written to the command topic.
   */
  public Future<CommandStatus> registerQueuedStatement(CommandId commandId) {
    statusStore.put(
        commandId,
        new CommandStatus(CommandStatus.Status.QUEUED, "Statement written to command topic")
    );

    CommandStatusFuture result;
    synchronized (statusFutures) {
      result = statusFutures.get(commandId);
      if (result != null) {
        return result;
      } else {
        result = new CommandStatusFuture(commandId);
        statusFutures.put(commandId, result);
        return result;
      }
    }
  }

  private void completeStatusFuture(CommandId commandId, CommandStatus commandStatus) {
    synchronized (statusFutures) {
      CommandStatusFuture statusFuture = statusFutures.get(commandId);
      if (statusFuture != null) {
        statusFuture.complete(commandStatus);
      } else {
        CommandStatusFuture newStatusFuture = new CommandStatusFuture(commandId);
        newStatusFuture.complete(commandStatus);
        statusFutures.put(commandId, newStatusFuture);
      }
    }
  }

  private Map<Long, CommandId> getTerminatedQueries(Map<CommandId, Command> commands) {
    Map<Long, CommandId> result = new HashMap<>();

    for (Map.Entry<CommandId, Command> commandEntry : commands.entrySet()) {
      CommandId commandId = commandEntry.getKey();
      String command = commandEntry.getValue().getStatement();
      Matcher terminateMatcher = TERMINATE_PATTERN.matcher(command.toUpperCase());
      if (terminateMatcher.matches()) {
        Long queryId = Long.parseLong(terminateMatcher.group(1));
        result.put(queryId, commandId);
      }
    }

    return result;
  }

  /**
   * Attempt to execute a single statement.
//   * @param statementString The string containing the statement to be executed
   * @param command The string containing the statement to be executed
   * @param commandId The ID to be used to track the status of the command
   * @param terminatedQueries An optional map from terminated query IDs to the commands that
   *                          requested their termination
   * @throws Exception TODO: Refine this.
   */
  private void handleStatementWithTerminatedQueries(
      Command command,
      CommandId commandId,
      Map<Long, CommandId> terminatedQueries
  ) throws Exception {
    try {
      String statementString = command.getStatement();
      statusStore.put(
          commandId,
          new CommandStatus(CommandStatus.Status.PARSING, "Parsing statement")
      );
      Statement statement = statementParser.parseSingleStatement(statementString);
      statusStore.put(
          commandId,
          new CommandStatus(CommandStatus.Status.EXECUTING, "Executing statement")
      );
      executeStatement(statement, command, commandId, terminatedQueries);
    } catch (WakeupException exception) {
      throw exception;
    } catch (Exception exception) {
      String stackTraceString = ExceptionUtil.stackTraceToString(exception);
      log.error(stackTraceString);
      CommandStatus errorStatus = new CommandStatus(CommandStatus.Status.ERROR, stackTraceString);
      statusStore.put(commandId, errorStatus);
      completeStatusFuture(commandId, errorStatus);
    }
  }

  private void executeStatement(
      Statement statement,
      Command command,
      CommandId commandId,
      Map<Long, CommandId> terminatedQueries
  ) throws Exception {
    String statementStr = command.getStatement();

    DDLCommandResult result = null;
    String successMessage = "";

    if (statement instanceof RegisterTopic
        || statement instanceof CreateStream
        || statement instanceof CreateTable
        || statement instanceof DropTopic
        || statement instanceof DropStream
        || statement instanceof DropTable
        ) {
      result =
          ksqlEngine.getQueryEngine().handleDdlStatement(statement, command.getStreamsProperties());
    } else if (statement instanceof CreateStreamAsSelect) {
      CreateStreamAsSelect createStreamAsSelect = (CreateStreamAsSelect) statement;
      QuerySpecification querySpecification =
          (QuerySpecification) createStreamAsSelect.getQuery().getQueryBody();
      Query query = ksqlEngine.addInto(
          createStreamAsSelect.getQuery(),
          querySpecification,
          createStreamAsSelect.getName().getSuffix(),
          createStreamAsSelect.getProperties(),
          createStreamAsSelect.getPartitionByColumn()
      );
      if (startQuery(statementStr, query, commandId, terminatedQueries, command.getStreamsProperties())) {
        successMessage = "Stream created and running";
      } else {
        return;
      }
    } else if (statement instanceof CreateTableAsSelect) {
      CreateTableAsSelect createTableAsSelect = (CreateTableAsSelect) statement;
      QuerySpecification querySpecification =
          (QuerySpecification) createTableAsSelect.getQuery().getQueryBody();
      Query query = ksqlEngine.addInto(
          createTableAsSelect.getQuery(),
          querySpecification,
          createTableAsSelect.getName().getSuffix(),
          createTableAsSelect.getProperties(),
          Optional.empty()
      );
      if (startQuery(statementStr, query, commandId, terminatedQueries, command.getStreamsProperties())) {
        successMessage = "Table created and running";
      } else {
        return;
      }
    } else if (statement instanceof TerminateQuery) {
      terminateQuery((TerminateQuery) statement);
      successMessage = "Query terminated.";
    } else if (statement instanceof RunScript) {
      if (command.getStreamsProperties().containsKey(DdlConfig.SCHEMA_FILE_CONTENT_PROPERTY)) {
        String queries =
            (String) command.getStreamsProperties().get(DdlConfig.SCHEMA_FILE_CONTENT_PROPERTY);
        List<QueryMetadata> queryMetadataList = ksqlEngine.buildMultipleQueries(false, queries,
                                            command.getStreamsProperties());
        for (QueryMetadata queryMetadata : queryMetadataList) {
          if (queryMetadata instanceof PersistentQueryMetadata) {
            PersistentQueryMetadata persistentQueryMetadata = (PersistentQueryMetadata) queryMetadata;
            persistentQueryMetadata.getKafkaStreams().start();
          }
        }
      } else {
        throw new KsqlException("No statements received for LOAD FROM FILE.");
      }

    }else {
      throw new Exception(String.format(
          "Unexpected statement type: %s",
          statement.getClass().getName()
      ));
    }
    // TODO: change to unified return message
    CommandStatus successStatus = new CommandStatus(CommandStatus.Status.SUCCESS,
        result != null ? result.getMessage(): successMessage);
    statusStore.put(commandId, successStatus);
    completeStatusFuture(commandId, successStatus);
  }

  private boolean startQuery(
      String queryString,
      Query query,
      CommandId commandId,
      Map<Long, CommandId> terminatedQueries,
      Map<String, Object> queryConfigProperties
  ) throws Exception {
    if (query.getQueryBody() instanceof QuerySpecification) {
      QuerySpecification querySpecification = (QuerySpecification) query.getQueryBody();
      Optional<Relation> into = querySpecification.getInto();
      if (into.isPresent() && into.get() instanceof Table) {
        Table table = (Table) into.get();
        if (ksqlEngine.getMetaStore().getSource(table.getName().getSuffix()) != null) {
          throw new Exception(String.format(
              "Sink specified in INTO clause already exists: %s",
              table.getName().getSuffix().toUpperCase()
          ));
        }
      }
    }

    QueryMetadata queryMetadata = ksqlEngine.buildMultipleQueries(
        false, queryString, queryConfigProperties).get(0);

    if (queryMetadata instanceof PersistentQueryMetadata) {
      PersistentQueryMetadata persistentQueryMetadata = (PersistentQueryMetadata) queryMetadata;
      long queryId = persistentQueryMetadata.getId();

      if (terminatedQueries != null && terminatedQueries.containsKey(queryId)) {
        CommandId terminateId = terminatedQueries.get(queryId);
        statusStore.put(
            terminateId,
            new CommandStatus(CommandStatus.Status.SUCCESS, "Termination request granted")
        );
        statusStore.put(
            commandId,
            new CommandStatus(CommandStatus.Status.TERMINATED, "Query terminated")
        );
        ksqlEngine.terminateQuery(queryId, false);
        return false;
      } else {
        persistentQueryMetadata.getKafkaStreams().start();
        return true;
      }

    } else {
      throw new Exception(String.format(
          "Unexpected query metadata type: %s; was expecting %s",
          queryMetadata.getClass().getCanonicalName(),
          PersistentQueryMetadata.class.getCanonicalName()
      ));
    }
  }

  private void terminateQuery(TerminateQuery terminateQuery) throws Exception {
    long queryId = terminateQuery.getQueryId();
    QueryMetadata queryMetadata = ksqlEngine.getPersistentQueries().get(queryId);
    if (!ksqlEngine.terminateQuery(queryId, true)) {
      throw new Exception(String.format("No running query with id %d was found", queryId));
    }

    CommandId.Type commandType;
    DataSource.DataSourceType sourceType =
        queryMetadata.getOutputNode().getTheSourceNode().getDataSourceType();
    switch (sourceType) {
      case KTABLE:
        commandType = CommandId.Type.TABLE;
        break;
      case KSTREAM:
        commandType = CommandId.Type.STREAM;
        break;
      default:
        throw new
            Exception(String.format("Unexpected source type for running query: %s", sourceType));
    }

    String queryEntity =
        ((KsqlStructuredDataOutputNode) queryMetadata.getOutputNode()).getKsqlTopic().getName();

    CommandId queryStatementId = new CommandId(commandType, queryEntity);
    statusStore.put(
        queryStatementId,
        new CommandStatus(CommandStatus.Status.TERMINATED, "Query terminated")
    );
  }

  private class CommandStatusFuture implements Future<CommandStatus> {

    private final CommandId commandId;
    private final AtomicReference<CommandStatus> result;

    public CommandStatusFuture(CommandId commandId) {
      this.commandId = commandId;
      this.result = new AtomicReference<>(null);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false; // TODO: Is an implementation of this method necessary?
    }

    @Override
    public boolean isCancelled() {
      return false; // TODO: Is an implementation of this method necessary?
    }

    @Override
    public boolean isDone() {
      return result.get() != null;
    }

    @Override
    public CommandStatus get() throws InterruptedException {
      synchronized (result) {
        while (result.get() == null) {
          result.wait();
        }
        removeFromFutures();
        return result.get();
      }
    }

    @Override
    public CommandStatus get(long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException {
      long endTimeMs = System.currentTimeMillis() + unit.toMillis(timeout);
      synchronized (result) {
        while (System.currentTimeMillis() < endTimeMs && result.get() == null) {
          result.wait(Math.max(1, endTimeMs - System.currentTimeMillis()));
        }
        if (result.get() == null) {
          throw new TimeoutException();
        }
        removeFromFutures();
        return result.get();
      }
    }

    private void complete(CommandStatus result) {
      synchronized (this.result) {
        this.result.set(result);
        this.result.notifyAll();
      }
    }

    private void removeFromFutures() {
      synchronized (statusFutures) {
        statusFutures.remove(commandId);
      }
    }
  }
}
