/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lambdaworks.redis.dynamic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.lambdaworks.redis.LettuceFutures;
import com.lambdaworks.redis.RedisFuture;
import com.lambdaworks.redis.api.StatefulConnection;
import com.lambdaworks.redis.dynamic.batch.BatchException;
import com.lambdaworks.redis.dynamic.batch.CommandBatching;
import com.lambdaworks.redis.dynamic.parameter.ExecutionSpecificParameters;
import com.lambdaworks.redis.protocol.AsyncCommand;
import com.lambdaworks.redis.protocol.RedisCommand;

/**
 * Executable command that uses a {@link Batcher} for command execution.
 * 
 * @author Mark Paluch
 * @since 5.0
 */
class BatchExecutableCommand implements ExecutableCommand {

    private final CommandMethod commandMethod;
    private final CommandFactory commandFactory;
    private final Batcher batcher;
    private final StatefulConnection<Object, Object> connection;
    private final ExecutionSpecificParameters parameters;
    private final boolean async;

    BatchExecutableCommand(CommandMethod commandMethod, CommandFactory commandFactory, Batcher batcher,
            StatefulConnection<Object, Object> connection) {

        this.commandMethod = commandMethod;
        this.commandFactory = commandFactory;
        this.batcher = batcher;
        this.parameters = (ExecutionSpecificParameters) commandMethod.getParameters();
        this.async = commandMethod.isFutureExecution();
        this.connection = connection;
    }

    @Override
    public Object execute(Object[] parameters) throws ExecutionException, InterruptedException {

        RedisCommand<Object, Object, Object> command = commandFactory.createCommand(parameters);

        CommandBatching batching = null;
        if (this.parameters.hasCommandBatchingIndex()) {
            batching = (CommandBatching) parameters[this.parameters.getCommandBatchingIndex()];
        }

        AsyncCommand<Object, Object, Object> asyncCommand = new AsyncCommand<>(command);

        if (async) {
            batcher.batch(asyncCommand, batching);
            return asyncCommand;
        }

        BatchTasks batchTasks = batcher.batch(asyncCommand, batching);

        return synchronize(batchTasks, connection);
    }

    protected static Object synchronize(BatchTasks batchTasks, StatefulConnection<Object, Object> connection) {

        if (batchTasks == BatchTasks.EMPTY) {
            return null;
        }

        long timeout = connection.getTimeout();
        TimeUnit unit = connection.getTimeoutUnit();

        BatchException exception = null;
        List<RedisCommand<?, ?, ?>> failures = null;
        for (RedisCommand<?, ?, ?> batchTask : batchTasks) {

            try {
                LettuceFutures.awaitAll(timeout, unit, (RedisFuture) batchTask);
            } catch (Exception e) {
                if (exception == null) {
                    failures = new ArrayList<>();
                    exception = new BatchException(failures);
                }

                failures.add(batchTask);
                exception.addSuppressed(e);
            }
        }

        if (exception != null) {
            throw exception;
        }

        return null;
    }

    @Override
    public CommandMethod getCommandMethod() {
        return commandMethod;
    }
}
