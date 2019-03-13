/*
 * Copyright 2019 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.ext.web.handler.graphql.impl;

import graphql.ExecutionInput;
import graphql.GraphQL;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.impl.NoStackTraceThrowable;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.graphql.GraphQLHandler;
import io.vertx.ext.web.handler.graphql.GraphQLHandlerOptions;
import org.dataloader.DataLoaderRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static java.util.stream.Collectors.toList;

/**
 * @author Thomas Segismont
 */
public class GraphQLHandlerImpl implements GraphQLHandler {

  private final GraphQL graphQL;
  private final GraphQLHandlerOptions options;

  private Function<RoutingContext, Object> queryContextFactory = rc -> rc;
  private Function<RoutingContext, DataLoaderRegistry> dataLoaderRegistryFactory = rc -> null;

  public GraphQLHandlerImpl(GraphQL graphQL, GraphQLHandlerOptions options) {
    Objects.requireNonNull(graphQL, "graphQL");
    Objects.requireNonNull(options, "options");
    this.graphQL = graphQL;
    this.options = options;
  }

  @Override
  public synchronized GraphQLHandler queryContext(Function<RoutingContext, Object> factory) {
    queryContextFactory = factory != null ? factory : rc -> rc;
    return this;
  }

  @Override
  public synchronized GraphQLHandler dataLoaderRegistry(Function<RoutingContext, DataLoaderRegistry> factory) {
    dataLoaderRegistryFactory = factory != null ? factory : rc -> null;
    return this;
  }

  @Override
  public void handle(RoutingContext rc) {
    HttpMethod method = rc.request().method();
    if (method == GET) {
      handleGet(rc);
    } else if (method == POST) {
      Buffer body = rc.getBody();
      if (body == null) {
        rc.request().bodyHandler(buffer -> handlePost(rc, buffer));
      } else {
        handlePost(rc, body);
      }
    } else {
      rc.fail(405);
    }
  }

  private void handleGet(RoutingContext rc) {
    String q = getQueryFromQueryParam(rc);
    if (q == null) {
      failQueryMissing(rc);
      return;
    }
    GraphQLQuery query = new GraphQLQuery().setQuery(q);
    Map<String, Object> variablesFromQueryParam;
    try {
      variablesFromQueryParam = getVariablesFromQueryParam(rc);
    } catch (Exception e) {
      rc.fail(400, e);
      return;
    }
    query.setVariables(variablesFromQueryParam);
    executeOne(rc, query);
  }

  private void handlePost(RoutingContext rc, Buffer body) {
    Map<String, Object> variablesFromQueryParm;
    try {
      variablesFromQueryParm = getVariablesFromQueryParam(rc);
    } catch (Exception e) {
      rc.fail(400, e);
      return;
    }

    String queryFromQueryParam = getQueryFromQueryParam(rc);
    if (queryFromQueryParam != null) {
      executeOne(rc, new GraphQLQuery().setQuery(queryFromQueryParam).setVariables(variablesFromQueryParm));
      return;
    }

    switch (getContentType(rc)) {
      case "application/json":
        handlePostJson(rc, body, variablesFromQueryParm);
        break;
      case "application/graphql":
        executeOne(rc, new GraphQLQuery().setQuery(body.toString()).setVariables(variablesFromQueryParm));
        break;
      default:
        rc.fail(415);
    }
  }

  private void handlePostJson(RoutingContext rc, Buffer body, Map<String, Object> variablesFromQueryParm) {
    GraphQLInput graphQLInput;
    try {
      graphQLInput = Json.decodeValue(body, GraphQLInput.class);
    } catch (Exception e) {
      rc.fail(400, e);
      return;
    }
    if (graphQLInput instanceof GraphQLBatch) {
      handlePostBatch(rc, (GraphQLBatch) graphQLInput, variablesFromQueryParm);
    } else if (graphQLInput instanceof GraphQLQuery) {
      handlePostQuery(rc, (GraphQLQuery) graphQLInput, variablesFromQueryParm);
    } else {
      rc.fail(500);
    }
  }

  private void handlePostBatch(RoutingContext rc, GraphQLBatch batch, Map<String, Object> variablesFromQueryParm) {
    if (!options.isRequestBatchingEnabled()) {
      rc.fail(400);
      return;
    }
    for (GraphQLQuery query : batch) {
      if (query.getQuery() == null) {
        failQueryMissing(rc);
        return;
      }
      if (query.getVariables() == null) {
        query.setVariables(variablesFromQueryParm);
      }
    }
    executeBatch(rc, batch);
  }

  private void executeBatch(RoutingContext rc, GraphQLBatch batch) {
    List<CompletableFuture<JsonObject>> results = batch.stream()
      .map(q -> execute(rc, q))
      .collect(toList());
    CompletableFuture.allOf((CompletableFuture<?>[]) results.toArray(new CompletableFuture<?>[0])).whenCompleteAsync((v, throwable) -> {
      JsonArray jsonArray = results.stream()
        .map(CompletableFuture::join)
        .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
      sendResponse(rc, jsonArray.toBuffer(), throwable);
    }, contextExecutor(rc));
  }

  private void handlePostQuery(RoutingContext rc, GraphQLQuery query, Map<String, Object> variablesFromQueryParm) {
    if (query.getQuery() == null) {
      failQueryMissing(rc);
      return;
    }
    if (query.getVariables() == null) {
      query.setVariables(variablesFromQueryParm);
    }
    executeOne(rc, query);
  }

  private void executeOne(RoutingContext rc, GraphQLQuery query) {
    execute(rc, query)
      .thenApply(JsonObject::toBuffer)
      .whenComplete((buffer, throwable) -> sendResponse(rc, buffer, throwable));
  }

  private CompletableFuture<JsonObject> execute(RoutingContext rc, GraphQLQuery query) {
    ExecutionInput.Builder builder = ExecutionInput.newExecutionInput();

    builder.query(query.getQuery());
    Map<String, Object> variables = query.getVariables();
    if (variables != null) {
      builder.variables(variables);
    }

    Function<RoutingContext, Object> qc;
    synchronized (this) {
      qc = queryContextFactory;
    }
    builder.context(qc.apply(rc));

    Function<RoutingContext, DataLoaderRegistry> dlr;
    synchronized (this) {
      dlr = dataLoaderRegistryFactory;
    }
    DataLoaderRegistry registry = dlr.apply(rc);
    if (registry != null) {
      builder.dataLoaderRegistry(registry);
    }

    return graphQL.executeAsync(builder.build()).thenApplyAsync(executionResult -> {
      return new JsonObject(executionResult.toSpecification());
    }, contextExecutor(rc));
  }

  private String getContentType(RoutingContext rc) {
    String contentType = rc.request().headers().get(HttpHeaders.CONTENT_TYPE);
    return contentType == null ? "application/json" : contentType.toLowerCase();
  }

  private String getQueryFromQueryParam(RoutingContext rc) {
    return rc.queryParams().get("query");
  }

  private Map<String, Object> getVariablesFromQueryParam(RoutingContext rc) throws Exception {
    String variablesParam = rc.queryParams().get("variables");
    if (variablesParam == null) {
      return null;
    } else {
      return new JsonObject(variablesParam).getMap();
    }
  }

  private void sendResponse(RoutingContext rc, Buffer buffer, Throwable throwable) {
    if (throwable == null) {
      rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json").end(buffer);
    } else {
      rc.fail(throwable);
    }
  }

  private void failQueryMissing(RoutingContext rc) {
    rc.fail(400, new NoStackTraceThrowable("Query is missing"));
  }

  private Executor contextExecutor(RoutingContext rc) {
    Context ctx = rc.vertx().getOrCreateContext();
    return command -> ctx.runOnContext(v -> command.run());
  }
}
