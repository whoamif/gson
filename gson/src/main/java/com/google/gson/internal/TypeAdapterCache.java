/*
 * Copyright (C) 2008 Google Inc.
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
 */

package com.google.gson.internal;

import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.internal.bind.SerializationDelegatingTypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe cache for {@link TypeAdapter} instances.
 *
 * <p>Responsible for two things:
 * <ol>
 *   <li>A persistent, cross-thread cache ({@code typeTokenCache}) that stores fully resolved
 *       adapters so they are never created more than once per type.
 *   <li>A per-call, thread-local map ({@code threadLocalAdapterResults}) that prevents infinite
 *       recursion when resolving a cyclic type graph (e.g. {@code TypeA → TypeB → TypeA}).
 *       During resolution, a {@link FutureTypeAdapter} placeholder is registered for the type
 *       being resolved; once the real adapter is ready the placeholder is wired up and the
 *       resolved adapters are published to the shared cache.
 * </ol>
 *
 * <p>Instances of this class are thread-safe.
 */
public final class TypeAdapterCache {

    // -------------------------------------------------------------------------
    // Champs
    // -------------------------------------------------------------------------

    /**
     * Shared, cross-thread cache. Maps a fully resolved {@link TypeToken} to its {@link TypeAdapter}.
     * Written once per type (first resolution wins) and read on every subsequent call.
     */
    private final ConcurrentMap<TypeToken<?>, TypeAdapter<?>> typeTokenCache =
            new ConcurrentHashMap<>();

    /**
     * Guards against reentrant calls to {@link #getAdapter} within the same thread.
     *
     * <p>When {@code getAdapter} is called for type T and T's factory itself calls
     * {@code getAdapter(T)} again, we return the {@link FutureTypeAdapter} placeholder that was
     * registered at the start of the outer call. Once the outer call finishes, the placeholder is
     * wired to the real adapter and all resolved adapters are published to {@link #typeTokenCache}.
     *
     * <p>The map is {@code null} when no resolution is in progress on the current thread.
     */
    @SuppressWarnings("ThreadLocalUsage")
    private final ThreadLocal<Map<TypeToken<?>, TypeAdapter<?>>> threadLocalAdapterResults =
            new ThreadLocal<>();

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    /**
     * Returns the cached adapter for {@code type}, or {@code null} if none is cached yet.
     *
     * <p>Checks the shared cache first, then the thread-local in-progress map (to handle cyclic
     * dependencies within the same resolution call stack).
     */
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> get(TypeToken<T> type) {
        // 1. Shared cache — fast path, covers the vast majority of calls.
        TypeAdapter<?> cached = typeTokenCache.get(type);
        if (cached != null) {
            return (TypeAdapter<T>) cached;
        }

        // 2. Thread-local in-progress map — handles cyclic dependencies.
        Map<TypeToken<?>, TypeAdapter<?>> threadCalls = threadLocalAdapterResults.get();
        if (threadCalls != null) {
            return (TypeAdapter<T>) threadCalls.get(type);
        }

        return null;
    }

    /**
     * Resolves the adapter for {@code type} by iterating over {@code factories}.
     *
     * <p>This method manages the thread-local in-progress map to detect and break cyclic
     * dependencies. On the outermost call (i.e. when no resolution is already in progress on this
     * thread), all successfully resolved adapters are published to the shared cache at the end.
     *
     * @param type     the type whose adapter is needed
     * @param factories the ordered list of factories to consult
     * @param gson     the {@code Gson} instance passed to each factory's {@code create} method;
     *                 typed as {@code Object} to avoid a circular compile-time dependency between
     *                 this internal class and the public {@code Gson} class — callers cast as needed
     * @return the resolved adapter, never {@code null}
     * @throws IllegalArgumentException if no factory can handle {@code type}
     */
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> resolve(
            TypeToken<T> type,
            List<TypeAdapterFactory> factories,
            com.google.gson.Gson gson) {

        Map<TypeToken<?>, TypeAdapter<?>> threadCalls = threadLocalAdapterResults.get();
        boolean isInitialAdapterRequest = (threadCalls == null);

        if (isInitialAdapterRequest) {
            threadCalls = new HashMap<>();
            threadLocalAdapterResults.set(threadCalls);
        } else {
            // Check if a resolution for this type is already in progress (cyclic dependency).
            TypeAdapter<T> ongoingCall = (TypeAdapter<T>) threadCalls.get(type);
            if (ongoingCall != null) {
                return ongoingCall;
            }
        }

        TypeAdapter<T> candidate = null;
        try {
            FutureTypeAdapter<T> call = new FutureTypeAdapter<>();
            threadCalls.put(type, call);

            for (TypeAdapterFactory factory : factories) {
                candidate = factory.create(gson, type);
                if (candidate != null) {
                    call.setDelegate(candidate);
                    threadCalls.put(type, candidate);
                    break;
                }
            }
        } finally {
            if (isInitialAdapterRequest) {
                threadLocalAdapterResults.remove();
            }
        }

        if (candidate == null) {
            throw new IllegalArgumentException(
                    "GSON (" + GsonBuildConfig.VERSION + ") cannot handle " + type);
        }

        if (isInitialAdapterRequest) {
            /*
             * Publish all resolved adapters to the shared cache.
             * This is only safe on the outermost call: a nested call (cyclic dependency
             * TypeA → TypeB → TypeA) must not publish TypeB's adapter while TypeA's is still
             * a FutureTypeAdapter placeholder — doing so would cache an unresolved proxy.
             * See https://github.com/google/gson/issues/625
             */
            typeTokenCache.putAll(threadCalls);
        }

        return candidate;
    }

    // -------------------------------------------------------------------------
    // Classe interne : FutureTypeAdapter
    // -------------------------------------------------------------------------

    /**
     * Proxy {@link TypeAdapter} used as a placeholder while a real adapter is being resolved.
     *
     * <p>Handles cyclic type graphs: when resolving type T requires an adapter for T itself,
     * this placeholder is returned immediately. Once the outer resolution completes, {@link
     * #setDelegate} wires the real adapter in.
     *
     * <p><b>Thread-safety:</b> {@link #setDelegate} must be called before this instance is
     * published to other threads (i.e. before it is stored in {@link #typeTokenCache}).
     */
    public static final class FutureTypeAdapter<T> extends SerializationDelegatingTypeAdapter<T> {

        private TypeAdapter<T> delegate = null;

        /**
         * Wires the real adapter. Must be called exactly once, before this instance is shared.
         *
         * @throws AssertionError if called more than once
         */
        public void setDelegate(TypeAdapter<T> typeAdapter) {
            if (delegate != null) {
                throw new AssertionError("Delegate is already set");
            }
            delegate = typeAdapter;
        }

        private TypeAdapter<T> delegate() {
            TypeAdapter<T> d = this.delegate;
            if (d == null) {
                // Happens if the adapter leaks to another thread before setDelegate() is called,
                // or if a TypeAdapterFactory uses this adapter directly during its own create() call.
                throw new IllegalStateException(
                        "Adapter for type with cyclic dependency has been used"
                                + " before dependency has been resolved");
            }
            return d;
        }

        @Override
        public TypeAdapter<T> getSerializationDelegate() {
            return delegate();
        }

        @Override
        public T read(JsonReader in) throws IOException {
            return delegate().read(in);
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
            delegate().write(out, value);
        }
    }
}