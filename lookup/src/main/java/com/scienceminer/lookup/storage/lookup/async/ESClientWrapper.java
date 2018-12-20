package com.scienceminer.lookup.storage.lookup.async;

import com.scienceminer.lookup.exception.ServiceException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ESClientWrapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(ESClientWrapper.class);

    private final ExecutorService executorService;
    private RestHighLevelClient esClient;

    final AtomicInteger counter;

    public ESClientWrapper(RestHighLevelClient esClient, int poolSize) {
        this.esClient = esClient;
        this.counter = new AtomicInteger(poolSize);
        this.executorService = new ThreadPoolExecutor(poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(poolSize), (r, executor) -> {
            throw new ServiceException(503, "Rejected request, try later");
        });

    }

    public SearchResponse searchSync(final SearchRequest request, final RequestOptions options) throws IOException {
        return esClient.search(request, options);
    }


    public CompletableFuture<Void> searchAsync(final SearchRequest request, final RequestOptions options,
                                               Consumer<SearchResponse> callback) {

        ActionListener<SearchResponse> listener = new ActionListener<SearchResponse>() {

            @Override
            public void onResponse(SearchResponse searchResponse) {
                final int i = counter.incrementAndGet();
                LOGGER.info("Got a response, freeing a spot: " + i);
                callback.accept(searchResponse);
            }

            @Override
            public void onFailure(Exception e) {
                final int i = counter.incrementAndGet();
                LOGGER.info("Got an error, freeing a spot: " + i);
                throw new ServiceException(503, "The request fail. Try again.", e);
            }
        };
        synchronized (counter) {
            if (counter.get() <= 0) {
                throw new ServiceException(503, "Cannot get more requests");
            }
            final int i = counter.decrementAndGet();
            LOGGER.info("Ready to call, occupying a spot: " + i);
        }
        
        final CompletableFuture<Void> searchResponseCompletableFuture = CompletableFuture
                .runAsync(() -> esClient.searchAsync(request, options, listener), executorService);

        searchResponseCompletableFuture.exceptionally(throwable -> {
            throw new ServiceException(503, "Error when completing the task", throwable);
        });
//        searchResponseCompletableFuture.thenAccept(callback::accept);

        return searchResponseCompletableFuture;
    }
}
