/*
 * Licensed to David Pilato (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package fr.pilato.elasticsearch.crawler.fs.client.v5;


import fr.pilato.elasticsearch.crawler.fs.client.ESBoolQuery;
import fr.pilato.elasticsearch.crawler.fs.client.ESDocumentField;
import fr.pilato.elasticsearch.crawler.fs.client.ESHighlightField;
import fr.pilato.elasticsearch.crawler.fs.client.ESMatchQuery;
import fr.pilato.elasticsearch.crawler.fs.client.ESPrefixQuery;
import fr.pilato.elasticsearch.crawler.fs.client.ESQuery;
import fr.pilato.elasticsearch.crawler.fs.client.ESRangeQuery;
import fr.pilato.elasticsearch.crawler.fs.client.ESSearchHit;
import fr.pilato.elasticsearch.crawler.fs.client.ESSearchRequest;
import fr.pilato.elasticsearch.crawler.fs.client.ESSearchResponse;
import fr.pilato.elasticsearch.crawler.fs.client.ESTermQuery;
import fr.pilato.elasticsearch.crawler.fs.client.ESTermsAggregation;
import fr.pilato.elasticsearch.crawler.fs.client.ESVersion;
import fr.pilato.elasticsearch.crawler.fs.client.ElasticsearchClient;
import fr.pilato.elasticsearch.crawler.fs.framework.JsonUtil;
import fr.pilato.elasticsearch.crawler.fs.settings.Elasticsearch;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettings;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static fr.pilato.elasticsearch.crawler.fs.client.ElasticsearchClientUtil.decodeCloudId;
import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.INDEX_SETTINGS_FILE;
import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.INDEX_SETTINGS_FOLDER_FILE;
import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.isNullOrEmpty;
import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.readJsonFile;

/**
 * Elasticsearch Client for Clusters running v5.
 */
public class ElasticsearchClientV5 implements ElasticsearchClient {

    private static final Logger logger = LogManager.getLogger(ElasticsearchClientV5.class);
    private final Path config;
    private final FsSettings settings;

    private RestHighLevelClient client = null;
    private BulkProcessor bulkProcessor = null;

    /**
     * Type name for Elasticsearch versions >= 5.0 and < 6.0
     * @deprecated Will be removed with Elasticsearch V8
     */
    @Deprecated
    private static final String INDEX_TYPE_DOC = "doc";
    private ThreadPool threadPool;
    private RestClient lowLevelClient;

    public ElasticsearchClientV5(Path config, FsSettings settings) {
        this.config = config;
        this.settings = settings;
    }

    @Override
    public byte compatibleVersion() {
        return 5;
    }

    @Override
    public void start() throws IOException {
        if (client != null) {
            // The client has already been initialized. Let's skip this again
            return;
        }

        lowLevelClient = buildRestClient(settings.getElasticsearch()).build();
        try {
            // Create an elasticsearch client
            this.client = new RestHighLevelClient(lowLevelClient);
            checkVersion();
            logger.info("Elasticsearch Client for version {}.x connected to a node running version {}", compatibleVersion(), getVersion());
        } catch (ElasticsearchStatusException e) {
            logger.debug("got an error while trying to connect to elasticsearch cluster");
            throw new IOException(e);
        } catch (Exception e) {
            logger.warn("failed to create elasticsearch client, disabling crawler...");
            throw e;
        }

        if (settings.getElasticsearch().getPipeline() != null &&
                !isExistingPipeline(settings.getElasticsearch().getPipeline())) {
            throw new RuntimeException("You defined pipeline:" + settings.getElasticsearch().getPipeline() +
                    ", but it does not exist.");
        }

        BiConsumer<BulkRequest, ActionListener<BulkResponse>> bulkConsumer =
                (request, bulkListener) -> client.bulkAsync(request, bulkListener);

        threadPool = new ThreadPool(Settings.builder().put("node.name", "fscrawler-client").build());
        bulkProcessor = new BulkProcessor.Builder(bulkConsumer, new DebugListener(logger), threadPool)
                .setBulkActions(settings.getElasticsearch().getBulkSize())
                .setFlushInterval(TimeValue.timeValueMillis(settings.getElasticsearch().getFlushInterval().millis()))
                .setBulkSize(new ByteSizeValue(settings.getElasticsearch().getByteSize().getBytes()))
                .build();
    }

    @Override
    public ESVersion getVersion() throws IOException {
        Version version = client.info().getVersion();
        return ESVersion.fromString(version.toString());
    }

    class DebugListener implements BulkProcessor.Listener {
        private final Logger logger;

        DebugListener(Logger logger) {
            this.logger = logger;
        }

        @Override public void beforeBulk(long executionId, BulkRequest request) {
            logger.trace("Sending a bulk request of [{}] requests", request.numberOfActions());
        }

        @Override public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
            logger.trace("Executed bulk request with [{}] requests", request.numberOfActions());
            if (response.hasFailures()) {
                final int[] failures = {0};
                response.iterator().forEachRemaining(bir -> {
                    if (bir.isFailed()) {
                        failures[0]++;
                        logger.debug("Error caught for [{}]/[{}]/[{}]: {}", bir.getIndex(),
                                bir.getType(), bir.getId(), bir.getFailureMessage());
                    }
                });
                logger.warn("Got [{}] failures of [{}] requests", failures[0], request.numberOfActions());
            }
        }

        @Override public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
            logger.warn("Got a hard failure when executing the bulk request", failure);
        }
    }

    /**
     * Create an index
     * @param index index name
     * @param ignoreErrors don't fail if the index already exists
     * @param indexSettings index settings if any
     * @throws IOException In case of error
     */
    public void createIndex(String index, boolean ignoreErrors, String indexSettings) throws IOException {
        logger.debug("create index [{}]", index);
        logger.trace("index settings: [{}]", indexSettings);

        try {
            Response response = lowLevelClient.performRequest("PUT", "/" + index,
                    Collections.emptyMap(), createEntity(indexSettings));
            logger.trace("create index response: {}", asMap(response));
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 400 &&
                    (e.getMessage().contains("index_already_exists_exception") || // ES 5.x
                            e.getMessage().contains("IndexAlreadyExistsException") )) { // ES 1.x and 2.x
                if (!ignoreErrors) {
                    throw new RuntimeException("index already exists");
                }
                logger.trace("index already exists. Ignoring error...");
                return;
            }
            throw e;
        }
    }

    /**
     * Check if an index exists
     * @param index index name
     * @return true if the index exists, false otherwise
     * @throws IOException In case of error
     */
    public boolean isExistingIndex(String index) throws IOException {
        logger.debug("is existing index [{}]", index);
        try {
            Response restResponse = lowLevelClient.performRequest("GET", "/" + index);
            logger.trace("get index metadata response: {}", asMap(restResponse));
            return true;
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                logger.debug("index [{}] does not exist", index);
                return false;
            }
            throw e;
        }
    }

    /**
     * Check if a pipeline exists
     * @param pipelineName pipeline name
     * @return true if the pipeline exists, false otherwise
     * @throws IOException In case of error
     */
    public boolean isExistingPipeline(String pipelineName) throws IOException {
        logger.debug("is existing pipeline [{}]", pipelineName);

        try {
            Response restResponse = lowLevelClient.performRequest("GET", "/_ingest/pipeline/" + pipelineName);
            logger.trace("get pipeline metadata response: {}", asMap(restResponse));
            return true;
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                logger.debug("pipeline [{}] does not exist", pipelineName);
                return false;
            }
            throw e;
        }
    }

    /**
     * Refresh an index
     * @param index index name
     * @throws IOException In case of error
     */
    public void refresh(String index) throws IOException {
        logger.debug("refresh index [{}]", index);

        String path = "/";

        if (index != null) {
            path += index + "/";
        }

        path += "_refresh";

        Response restResponse = lowLevelClient.performRequest("POST", path);
        logger.trace("refresh raw response: {}", asMap(restResponse));
    }

    /**
     * Wait for an index to become at least yellow (all primaries assigned)
     * @param index index name
     * @throws IOException In case of error
     */
    public void waitForHealthyIndex(String index) throws IOException {
        logger.debug("wait for yellow health on index [{}]", index);

        Response restResponse = lowLevelClient.performRequest("GET", "/_cluster/health/" + index,
                Collections.singletonMap("wait_for_status", "yellow"));
        logger.trace("health response: {}", asMap(restResponse));
    }

    /**
     * Reindex data from one index/type to another index
     * @param sourceIndex source index name
     * @param sourceType source type name
     * @param targetIndex target index name
     * @return The number of documents that have been reindexed
     * @throws IOException In case of error
     */
    public int reindex(String sourceIndex, String sourceType, String targetIndex) throws IOException {
        logger.debug("reindex [{}]/[{}] -> [{}]/[doc]", sourceIndex, sourceType, targetIndex);

        String reindexQuery = "{  \"source\": {\n" +
                "    \"index\": \"" + sourceIndex + "\",\n" +
                "    \"type\": \"" + sourceType + "\"\n" +
                "  },\n" +
                "  \"dest\": {\n" +
                "    \"index\": \"" + targetIndex + "\",\n" +
                "    \"type\": \"doc\"\n" +
                "  }\n" +
                "}\n";

        logger.trace("{}", reindexQuery);

        HttpEntity entity = EntityBuilder.create().setText(reindexQuery).setContentType(ContentType.APPLICATION_JSON).build();
        Response restResponse = lowLevelClient.performRequest("POST", "/_reindex", Collections.emptyMap(), entity);
        Map<String, Object> response = asMap(restResponse);
        logger.debug("reindex response: {}", response);

        return (response == null ? -1 : (int) response.get("total"));
    }

    /**
     * Fully removes a type from an index (removes data)
     * @param index index name
     * @param type type
     * @throws IOException In case of error
     */
    public void deleteByQuery(String index, String type) throws IOException {
        logger.debug("deleteByQuery [{}]/[{}]", index, type);

        String deleteByQuery = "{\n" +
                "  \"query\": {\n" +
                "    \"match_all\": {}\n" +
                "  }\n" +
                "}";

        HttpEntity entity = EntityBuilder.create().setText(deleteByQuery).setContentType(ContentType.APPLICATION_JSON).build();
        Response restResponse = lowLevelClient.performRequest("POST", "/" + index + "/" + type + "/_delete_by_query", Collections.emptyMap(), entity);
        Map<String, Object> response = asMap(restResponse);
        logger.debug("reindex response: {}", response);
    }

    // Utility methods

    public boolean isIngestSupported() {
        return true;
    }

    public String getDefaultTypeName() {
        return INDEX_TYPE_DOC;
    }

    @Override
    public void index(String index, String type, String id, String json, String pipeline) {
        bulkProcessor.add(new IndexRequest(index, type, id).setPipeline(pipeline).source(json, XContentType.JSON));
    }

    @Override
    public void indexSingle(String index, String type, String id, String json) throws IOException {
        IndexRequest request = new IndexRequest(index, type, id);
        request.source(json, XContentType.JSON);
        client.index(request);
    }

    @Override
    public void delete(String index, String type, String id) {
        bulkProcessor.add(new DeleteRequest(index, type, id));
    }

    @Override
    public void close() throws IOException {
        logger.debug("Closing Elasticsearch client manager");
        if (bulkProcessor != null) {
            try {
                bulkProcessor.awaitClose(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.warn("Did not succeed in closing the bulk processor for documents", e);
                throw new IOException(e);
            }
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        if (lowLevelClient != null) {
            lowLevelClient.close();
        }
    }

    private static RestClientBuilder buildRestClient(Elasticsearch settings) {
        List<HttpHost> hosts = new ArrayList<>(settings.getNodes().size());
        settings.getNodes().forEach(node -> {
            if (node.getCloudId() != null) {
                // We have a cloud id which simplifies all
                hosts.add(HttpHost.create(decodeCloudId(node.getCloudId())));
            } else {
                hosts.add(HttpHost.create(node.getUrl()));
            }
        });

        RestClientBuilder builder = RestClient.builder(hosts.toArray(new HttpHost[hosts.size()]));

        if (settings.getUsername() != null) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(settings.getUsername(), settings.getPassword()));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        return builder;
    }

    public void createIndices() throws Exception {
        String elasticsearchVersion;
        Path jobMappingDir = config.resolve(settings.getName()).resolve("_mappings");

        // Let's read the current version of elasticsearch cluster
        Version version = client.info().getVersion();
        logger.debug("FS crawler connected to an elasticsearch [{}] node.", version.toString());

        elasticsearchVersion = Byte.toString(version.major);

        // If needed, we create the new settings for this files index
        if (!settings.getFs().isAddAsInnerObject() || (!settings.getFs().isJsonSupport() && !settings.getFs().isXmlSupport())) {
            createIndex(jobMappingDir, elasticsearchVersion, INDEX_SETTINGS_FILE, settings.getElasticsearch().getIndex());
        } else {
            createIndex(settings.getElasticsearch().getIndex(), true, null);
        }

        // If needed, we create the new settings for this folder index
        if (settings.getFs().isIndexFolders()) {
            createIndex(jobMappingDir, elasticsearchVersion, INDEX_SETTINGS_FOLDER_FILE, settings.getElasticsearch().getIndexFolder());
        } else {
            createIndex(settings.getElasticsearch().getIndexFolder(), true, null);
        }
    }

    @Override
    public ESSearchResponse search(ESSearchRequest request) throws IOException {

        SearchRequest searchRequest = new SearchRequest();
        if (!isNullOrEmpty(request.getIndex())) {
            searchRequest.indices(request.getIndex());
        }

        SearchSourceBuilder ssb = new SearchSourceBuilder();
        if (request.getSize() != null) {
            ssb.size(request.getSize());
        }
        if (!request.getFields().isEmpty()) {
            ssb.storedFields(request.getFields());
        }
        if (request.getESQuery() != null) {
            ssb.query(toElasticsearchQuery(request.getESQuery()));
        }
        if (!isNullOrEmpty(request.getSort())) {
            ssb.sort(request.getSort());
        }
        for (String highlighter : request.getHighlighters()) {
            ssb.highlighter(new HighlightBuilder().field(highlighter));
        }
        for (ESTermsAggregation aggregation : request.getAggregations()) {
            ssb.aggregation(AggregationBuilders.terms(aggregation.getName()).field(aggregation.getField()));
        }

        searchRequest.source(ssb);
        searchRequest.indicesOptions(IndicesOptions.lenientExpandOpen());

        SearchResponse response = client.search(searchRequest);
        ESSearchResponse esSearchResponse = new ESSearchResponse();
        if (response.getHits() != null) {
            for (SearchHit hit : response.getHits()) {
                ESSearchHit esSearchHit = new ESSearchHit();
                if (!hit.getFields().isEmpty()) {
                    Map<String, ESDocumentField> esFields = new HashMap<>();
                    for (Map.Entry<String, SearchHitField> entry : hit.getFields().entrySet()) {
                        esFields.put(entry.getKey(), new ESDocumentField(entry.getKey(), entry.getValue().getValues()));
                    }
                    esSearchHit.setFields(esFields);
                }
                esSearchHit.setIndex(hit.getIndex());
                esSearchHit.setId(hit.getId());
                esSearchHit.setSourceAsMap(hit.getSourceAsMap());
                esSearchHit.setSourceAsString(hit.getSourceAsString());

                hit.getHighlightFields().forEach((key, value) -> {
                    String[] texts = new String[value.fragments().length];
                    for (int i = 0; i < value.fragments().length; i++) {
                        Text fragment = value.fragments()[i];
                        texts[i] = fragment.string();
                    }
                    esSearchHit.addHighlightField(key, new ESHighlightField(key, texts));
                });

                esSearchResponse.addHit(esSearchHit);
            }

            esSearchResponse.setTotalHits(response.getHits().getTotalHits());

            if (response.getAggregations() != null) {
                for (String name : response.getAggregations().asMap().keySet()) {
                    Terms termsAgg = response.getAggregations().get(name);
                    ESTermsAggregation aggregation = new ESTermsAggregation(name, null);
                    for (Terms.Bucket bucket : termsAgg.getBuckets()) {
                        aggregation.addBucket(new ESTermsAggregation.ESTermsBucket(bucket.getKeyAsString(), bucket.getDocCount()));
                    }
                    esSearchResponse.addAggregation(name, aggregation);
                }
            }
        }

        return esSearchResponse;
    }

    private QueryBuilder toElasticsearchQuery(ESQuery query) {
        if (query instanceof ESTermQuery) {
            ESTermQuery esQuery = (ESTermQuery) query;
            return QueryBuilders.termQuery(esQuery.getField(), esQuery.getValue());
        }
        if (query instanceof ESMatchQuery) {
            ESMatchQuery esQuery = (ESMatchQuery) query;
            return QueryBuilders.matchQuery(esQuery.getField(), esQuery.getValue());
        }
        if (query instanceof ESPrefixQuery) {
            ESPrefixQuery esQuery = (ESPrefixQuery) query;
            return QueryBuilders.prefixQuery(esQuery.getField(), esQuery.getValue());
        }
        if (query instanceof ESRangeQuery) {
            ESRangeQuery esQuery = (ESRangeQuery) query;
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery(esQuery.getField());
            if (esQuery.getFrom() != null) {
                rangeQuery.from(esQuery.getFrom());
            }
            if (esQuery.getTo() != null) {
                rangeQuery.to(esQuery.getTo());
            }
            return rangeQuery;
        }
        if (query instanceof ESBoolQuery) {
            ESBoolQuery esQuery = (ESBoolQuery) query;
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
            for (ESQuery clause : esQuery.getMustClauses()) {
                boolQuery.must(toElasticsearchQuery(clause));
            }
            return boolQuery;
        }
        throw new IllegalArgumentException("Query " + query.getClass().getSimpleName() + " not implemented yet");
    }

    @Override
    public void deleteIndex(String index) throws IOException {
        try {
            Response response = lowLevelClient.performRequest("DELETE", "/" + index);
            logger.trace("delete index response: {}", asMap(response));
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                logger.trace("index does not exist. Ignoring error...");
                return;
            }
            throw e;
        }
    }

    @Override
    public void flush() {
        bulkProcessor.flush();
    }

    @Override
    public void performLowLevelRequest(String method, String endpoint, String jsonEntity) throws IOException {
        HttpEntity entity = EntityBuilder.create().setText(jsonEntity).setContentType(ContentType.APPLICATION_JSON).build();
        lowLevelClient.performRequest(method, endpoint, Collections.emptyMap(), entity);
    }

    @Override
    public ESSearchHit get(String index, String type, String id) throws IOException {
        GetRequest request = new GetRequest(index, type, id);
        GetResponse response = client.get(request);
        ESSearchHit hit = new ESSearchHit();
        hit.setIndex(response.getIndex());
        hit.setId(response.getId());
        hit.setVersion(response.getVersion());
        hit.setSourceAsMap(response.getSourceAsMap());
        return hit;
    }

    @Override
    public boolean exists(String index, String type, String id) throws IOException {
        return client.exists(new GetRequest(index, type, id));
    }

    private void createIndex(Path jobMappingDir, String elasticsearchVersion, String indexSettingsFile, String indexName) throws Exception {
        try {
            // If needed, we create the new settings for this files index
            String indexSettings = readJsonFile(jobMappingDir, config, elasticsearchVersion, indexSettingsFile);

            createIndex(indexName, true, indexSettings);
        } catch (Exception e) {
            logger.warn("failed to create index [{}], disabling crawler...", indexName);
            throw e;
        }
    }

    static Map<String, Object> asMap(Response response) {
        try {
            if (response.getEntity() == null) {
                return null;
            }
            return JsonUtil.asMap(response.getEntity().getContent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public HttpEntity createEntity(String json) {
        if (isNullOrEmpty(json)) {
            return null;
        }         
        return EntityBuilder.create().setText(json).setContentType(ContentType.APPLICATION_JSON).build();
    }
}
