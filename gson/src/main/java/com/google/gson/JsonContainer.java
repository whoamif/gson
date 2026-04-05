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

package com.google.gson;

/**
 * Abstract base class for JSON container types: {@link JsonObject} and {@link JsonArray}.
 *
 * <p>Both containers share a common notion of {@linkplain #size() size} and
 * {@linkplain #isEmpty() emptiness}. This class centralises that contract so that each
 * subclass only needs to implement {@link #size()} against its own backing collection;
 * {@link #isEmpty()} is then derived automatically and need not be duplicated.
 *
 * <p>All other behaviour (element storage, access, {@code deepCopy()}, …) remains in the
 * concrete subclasses because it depends on the specific data structure they use.
 *
 * @since 2.12.0
 */
public abstract class JsonContainer extends JsonElement {

    /** Package-private constructor — only {@link JsonObject} and {@link JsonArray} may extend this. */
    @SuppressWarnings("deprecation") // superclass constructor
    JsonContainer() {}

    /**
     * Returns the number of elements held by this container.
     *
     * <ul>
     *   <li>For a {@link JsonObject} this is the number of name/value pairs.
     *   <li>For a {@link JsonArray} this is the number of array elements.
     * </ul>
     *
     * @return the element count, always &ge; 0.
     */
    public abstract int size();

    /**
     * Returns {@code true} if this container holds no elements.
     *
     * <p>The default implementation delegates to {@link #size()}, so subclasses do not need to
     * override this method unless they can provide a more efficient check.
     *
     * @return {@code true} if {@link #size()} returns {@code 0}.
     */
    public boolean isEmpty() {
        return size() == 0;
    }
}