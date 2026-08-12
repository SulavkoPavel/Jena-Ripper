# Source analysis and refactoring report

## Sparkle architecture found

The original `C:\Users\Павел\IdeaProjects\sparkle` project is a static, build-tool-free frontend with two pages (`Start/start.html` and `Graph/graph.html`), BEM CSS, and D3 v5 loaded from a CDN.

- `Start/start.js` stores the Fuseki URL, prefix, initial resource, username, and password in `localStorage`.
- `Graph/graph.js` builds SPARQL text in the browser and sends a synchronous `XMLHttpRequest` directly to Fuseki with basic credentials.
- The frontend parses SPARQL Results JSON and distinguishes links from literals using a configured string prefix.
- D3 provides the reusable behavior: force layout, collision, zoom, drag, labels, directed edges, node selection, and click-to-expand.
- The direct Fuseki URL, browser-owned credentials, synchronous HTTP, SPARQL construction, and RDF-to-UI conversion were the dependencies to remove.

The visual interaction was retained and moved to `frontend/src/graph.js`. The obsolete connection form and credentials were removed because the backend now owns the configured dataset.

## cim-app reference examined

The Jena module under `C:\Users\Павел\IdeaProjects\cim-app\jena-app` contains both direct graph traversal and SPARQL services. The reusable patterns selected were:

- `TDB2Factory.connectDataset(...)` with a path supplied outside the query logic;
- explicit read/write transactions (`dataset.begin(...)`/`end()` and Jena `Txn` helpers);
- try-with-resources around `QueryExecution`;
- direct `DatasetGraph.find(...)` traversal where SPARQL is unnecessary;
- separation between services that obtain RDF data and code that converts it to response models.

Jena Ripper uses the modern `Txn.calculateRead` form to guarantee transaction cleanup. Every `QueryExecution` is scoped by try-with-resources. None of the CIM domain entities, authorization, Redis, PostgreSQL, JPA, file workflows, or portal services were copied.

## Resulting design

The backend has a REST/controller layer, use-case services, graph DTO mapping, and a small Jena repository layer. It opens one application-owned `Dataset` bean and closes it during shutdown. TDB2 is configured by YAML/environment; an in-memory provider demonstrates that another dataset implementation can be added without changing the controllers or frontend.

Resource details normalize RDF types and literal properties. Neighborhood responses normalize relations into stable nodes and edges, mark their direction relative to the requested resource, and include both default and named graph quads. Search uses a parameterized read-only SPARQL query for URI and common label predicates; user input is bound as a literal instead of interpolated into query text.

The frontend now has one asynchronous API client. It no longer knows Fuseki URLs, credentials, RDF prefixes, or SPARQL result formats. Graph state is merged by stable node/edge IDs so repeated expansion does not duplicate data.

## Fuseki status

No Fuseki configuration, endpoint, dependency, credential, or browser call remains. Fuseki can be removed completely for the implemented local TDB2 scenario. A future remote provider could use Fuseki behind the backend without changing the frontend API.

## Verification performed

- Backend integration tests start Spring with an in-memory transactional dataset and verify dataset information, node mapping, incoming-neighbor expansion, search, and invalid-URI handling.
- The Maven test suite passes (2 tests, 0 failures).
- The Vite production frontend build passes.

