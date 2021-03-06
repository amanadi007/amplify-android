/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.datastore.syncengine;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.api.graphql.GraphQLResponse;
import com.amplifyframework.api.graphql.SubscriptionType;
import com.amplifyframework.core.Action;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.core.Consumer;
import com.amplifyframework.core.async.Cancelable;
import com.amplifyframework.core.model.Model;
import com.amplifyframework.core.model.ModelProvider;
import com.amplifyframework.datastore.AmplifyDisposables;
import com.amplifyframework.datastore.DataStoreChannelEventName;
import com.amplifyframework.datastore.DataStoreException;
import com.amplifyframework.datastore.appsync.AppSync;
import com.amplifyframework.datastore.appsync.ModelWithMetadata;
import com.amplifyframework.hub.HubChannel;
import com.amplifyframework.hub.HubEvent;
import com.amplifyframework.logging.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.ReplaySubject;

/**
 * Observes mutations occurring on a remote {@link AppSync} system. The mutations arrive
 * over a long-lived subscription, as {@link SubscriptionEvent}s.
 * For every type of model provided by a {@link ModelProvider}, the SubscriptionProcessor
 * marries mutated models back into the local DataStore, through the {@link Merger}.
 */
final class SubscriptionProcessor {
    private static final Logger LOG = Amplify.Logging.forNamespace("amplify:aws-datastore");
    private static final long SUBSCRIPTION_START_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

    private final AppSync appSync;
    private final ModelProvider modelProvider;
    private final Merger merger;
    private final CompositeDisposable ongoingOperationsDisposable;
    private ReplaySubject<SubscriptionEvent<? extends Model>> buffer;

    /**
     * Constructs a new SubscriptionProcessor.
     * @param appSync An App Sync endpoint from which to receive subscription events
     * @param modelProvider The processor will subscribe to changes for these types of models
     * @param merger A merger, to apply data back into local storage
     */
    SubscriptionProcessor(
            @NonNull AppSync appSync,
            @NonNull ModelProvider modelProvider,
            @NonNull Merger merger) {
        this.appSync = Objects.requireNonNull(appSync);
        this.modelProvider = Objects.requireNonNull(modelProvider);
        this.merger = Objects.requireNonNull(merger);
        this.ongoingOperationsDisposable = new CompositeDisposable();
    }

    /**
     * Start subscribing to model mutations.
     */
    synchronized void startSubscriptions() {
        int subscriptionCount = modelProvider.models().size() * SubscriptionType.values().length;
        // Create a latch with the number of subscriptions are requesting. Each of these will be
        // counted down when each subscription's onStarted event is called.
        CountDownLatch latch = new CountDownLatch(subscriptionCount);
        // Need to create a new buffer so we can properly handle retries and stop/start scenarios.
        // Re-using the same buffer has some unexpected results due to the replay aspect of the subject.
        buffer = ReplaySubject.create();

        Set<Observable<SubscriptionEvent<? extends Model>>> subscriptions = new HashSet<>();
        for (Class<? extends Model> clazz : modelProvider.models()) {
            for (SubscriptionType subscriptionType : SubscriptionType.values()) {
                subscriptions.add(subscriptionObservable(appSync, subscriptionType, latch, clazz));
            }
        }
        ongoingOperationsDisposable.add(Observable.merge(subscriptions)
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .subscribe(
                buffer::onNext,
                exception -> {
                    // If the downstream buffer already has an error, don't invoke it again.
                    if (!buffer.hasThrowable()) {
                        buffer.onError(exception);
                    }
                },
                buffer::onComplete
            ));
        boolean subscriptionsStarted;
        try {
            LOG.debug("Waiting for subscriptions to start.");
            subscriptionsStarted = latch.await(SUBSCRIPTION_START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            LOG.warn("Subscription operations were interrupted during setup.");
            return;
        }
        if (subscriptionsStarted) {
            Amplify.Hub.publish(HubChannel.DATASTORE,
                                HubEvent.create(DataStoreChannelEventName.SUBSCRIPTIONS_ESTABLISHED));
            LOG.info(String.format(Locale.US,
                "Began buffering subscription events for remote mutations %s to Cloud models of types %s.",
                modelProvider.models(), Arrays.toString(SubscriptionType.values())
            ));
        } else {
            LOG.warn("Subscription processor failed to start within the expected timeout.");
        }
    }

    @SuppressWarnings("unchecked") // (Class<T>) modelWithMetadata.getModel().getClass()
    private static <T extends Model> Observable<SubscriptionEvent<? extends Model>>
            subscriptionObservable(AppSync appSync,
                                   SubscriptionType subscriptionType,
                                   CountDownLatch latch,
                                   Class<T> clazz) {
        return Observable.<GraphQLResponse<ModelWithMetadata<T>>>create(emitter -> {
            SubscriptionMethod method = subscriptionMethodFor(appSync, subscriptionType);
            AtomicReference<String> subscriptionId = new AtomicReference<>();
            Cancelable cancelable = method.subscribe(
                clazz,
                token -> {
                    LOG.debug("Subscription started for " + token);
                    subscriptionId.set(token);
                    latch.countDown();
                },
                emitter::onNext,
                throwable -> {
                    // Only call onError if the Observable hasn't been disposed and if
                    // the subscription was actually started at one point (which we determine by whether
                    // the subscriptionId is null or not.)
                    if (!emitter.isDisposed()) {
                        LOG.debug("Invoking subscription onError emitter.");
                        emitter.onError(throwable);
                    }
                    if (latch.getCount() != 0) {
                        LOG.debug("Releasing latch due to an error.");
                        latch.countDown();
                    }
                },
                () -> {
                    LOG.debug("Subscription completed:" + subscriptionId.get());
                    emitter.onComplete();
                }
            );
            // When the observable is disposed, we need to call cancel() on the subscription
            // so it can properly dispose of resources if necessary. For the AWS API plugin,
            // this means means closing the underlying network connection.
            emitter.setDisposable(AmplifyDisposables.fromCancelable(cancelable));
        })
        .doOnError(subscriptionError ->
            LOG.warn(String.format(Locale.US,
                "An error occurred on the remote %s subscription for model %s.",
                clazz.getSimpleName(), subscriptionType.name()
            ), subscriptionError)
        )
        .subscribeOn(Schedulers.io())
        .observeOn(Schedulers.io())
        .map(SubscriptionProcessor::unwrapResponse)
        .map(modelWithMetadata -> SubscriptionEvent.<T>builder()
            .type(fromSubscriptionType(subscriptionType))
            .modelWithMetadata(modelWithMetadata)
            .modelClass((Class<T>) modelWithMetadata.getModel().getClass())
            .build()
        );
    }

    /**
     * Start draining mutations out of the mutation buffer.
     * This should be called after {@link #startSubscriptions()}.
     */
    void startDrainingMutationBuffer(Action onPipelineBroken) {
        ongoingOperationsDisposable.add(
            buffer
                .doOnSubscribe(disposable ->
                    LOG.info("Starting processing subscription data buffer.")
                )
                .flatMapCompletable(mutation -> merger.merge(mutation.modelWithMetadata()))
                .doOnError(failure -> LOG.warn("Reading subscriptions buffer has failed.", failure))
                .doOnComplete(() -> LOG.warn("Reading from subscriptions buffer is completed."))
                .onErrorComplete()
                .subscribe(onPipelineBroken::call)
        );
    }

    /**
     * Stop any active subscriptions, and stop draining the mutation buffer.
     */
    synchronized void stopAllSubscriptionActivity() {
        LOG.info("Stopping subscription processor.");
        ongoingOperationsDisposable.clear();
        LOG.info("Stopped subscription processor.");
    }

    @VisibleForTesting
    static SubscriptionMethod subscriptionMethodFor(
            AppSync appSync, SubscriptionType subscriptionType) throws DataStoreException {
        switch (subscriptionType) {
            case ON_UPDATE:
                return appSync::onUpdate;
            case ON_DELETE:
                return appSync::onDelete;
            case ON_CREATE:
                return appSync::onCreate;
            default:
                throw new DataStoreException(
                    "Failed to establish a model subscription.",
                    "Was a new subscription type created?"
                );
        }
    }

    private static <T extends Model> ModelWithMetadata<T> unwrapResponse(
            GraphQLResponse<? extends ModelWithMetadata<T>> response) throws DataStoreException {
        final String errorMessage;
        if (response.hasErrors()) {
            errorMessage = String.format("Errors on subscription: %s", response.getErrors());
        } else if (!response.hasData()) {
            errorMessage = "Empty data received on subscription.";
        } else {
            errorMessage = null;
        }
        if (errorMessage != null) {
            throw new DataStoreException(
                errorMessage, AmplifyException.REPORT_BUG_TO_AWS_SUGGESTION
            );
        }
        return response.getData();
    }

    private static SubscriptionEvent.Type fromSubscriptionType(SubscriptionType subscriptionType) {
        switch (subscriptionType) {
            case ON_CREATE:
                return SubscriptionEvent.Type.CREATE;
            case ON_DELETE:
                return SubscriptionEvent.Type.DELETE;
            case ON_UPDATE:
                return SubscriptionEvent.Type.UPDATE;
            default:
                throw new IllegalArgumentException("Unknown subscription type: " + subscriptionType);
        }
    }

    interface SubscriptionMethod {
        <T extends Model> Cancelable subscribe(
                @NonNull Class<T> clazz,
                @NonNull Consumer<String> onStart,
                @NonNull Consumer<GraphQLResponse<ModelWithMetadata<T>>> onResponse,
                @NonNull Consumer<DataStoreException> onFailure,
                @NonNull Action onComplete
        );
    }
}
