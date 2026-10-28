# REST paths and Gremlin requests

The REST listener uses paths such as `/versions`, `/graphspaces` and `/gremlin`.
`GET /apis` lists the available resource paths and methods. Each `url` starts
with `/` and is relative to the Server origin. For example, use
`http://localhost:8080/gremlin`, without adding `/apis` before the listed path.
When using a gateway with an external path prefix, retain that gateway prefix.

## Select a graph for synchronous Gremlin

GraphSpace names and graph names are case-sensitive. In a GraphSpace-enabled
Server, the graph binding is `<graphspace>-<graph>`, and its traversal-source
binding is `__g_<graphspace>-<graph>`. There is no implicit global `g`: a Server
can host multiple graphs. Supply aliases for the names used by the query:

```bash
curl -X POST 'http://localhost:8080/gremlin' \
  -H 'Content-Type: application/json' \
  -d '{"gremlin":"g.V().count()","aliases":{"graph":"DEFAULT-hugegraph","g":"__g_DEFAULT-hugegraph"}}'
```

For an authenticated Server, also supply its configured HTTP authentication
credentials, for example `curl -u "$HG_USER:$HG_PASSWORD"`. Request `bindings`
are query variables; they do not create graph bindings. An alias target must be
an existing graph or traversal-source binding, rather than a Gremlin expression
such as `graph.traversal()`.

For a standalone legacy graph with no GraphSpace, use `<graph>` and
`__g_<graph>` instead. The Java client builds the appropriate aliases from its
configured GraphSpace and graph. Verify the configured names before treating a
`Could not rebind` response as a graph-registration failure.

## Run long queries as tasks

Synchronous execution has a configured timeout (`evaluationTimeout` in
`gremlin-server.yaml`, 30000 milliseconds by default). Large label counts may
scan substantial data even when a whole-graph count is fast. Use an asynchronous
job when a query can exceed this limit:

```bash
curl -X POST 'http://localhost:8080/graphspaces/DEFAULT/graphs/hugegraph/jobs/gremlin' \
  -H 'Content-Type: application/json' \
  -d '{"gremlin":"g.V().hasLabel(\"person\").count()"}'
```

The response contains `task_id`. Poll
`GET /graphspaces/DEFAULT/graphs/hugegraph/tasks/{task_id}` until the task reports
a terminal status, then inspect `task_result`. A running task has not completed
its count. The job binds `graph` and `g` for the graph selected in its path.
Use the same authentication as for the other Server APIs.

## Create another graph

List graph spaces with `GET /graphspaces`, and create a graph using
`POST /graphspaces/DEFAULT/graphs/{graph}` with the graph configuration expected
by the backend. `POST /graphs` is not the graph-creation contract. Creating a
graph requires the corresponding Server permissions and backend configuration.
