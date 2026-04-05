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

import static com.google.gson.GsonBuilder.newImmutableList;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.internal.ConstructorConstructor;
import com.google.gson.internal.Excluder;
import com.google.gson.internal.GsonBuildConfig;
import com.google.gson.internal.Primitives;
import com.google.gson.internal.Streams;
import com.google.gson.internal.TypeAdapterCache;
import com.google.gson.internal.bind.JsonAdapterAnnotationTypeAdapterFactory;
import com.google.gson.internal.bind.JsonTreeReader;
import com.google.gson.internal.bind.JsonTreeWriter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * This is the main class for using Gson. Gson is typically used by first constructing a Gson
 * instance and then invoking {@link #toJson(Object)} or {@link #fromJson(String, Class)} methods on
 * it. Gson instances are Thread-safe so you can reuse them freely across multiple threads.
 *
 * <p>You can create a Gson instance by invoking {@code new Gson()} if the default configuration is
 * all you need. You can also use {@link GsonBuilder} to build a Gson instance with various
 * configuration options such as versioning support, pretty printing, custom newline, custom indent,
 * custom {@link JsonSerializer}s, {@link JsonDeserializer}s, and {@link InstanceCreator}s.
 *
 * <p>Here is an example of how Gson is used for a simple Class:
 *
 * <pre>
 * Gson gson = new Gson(); // Or use new GsonBuilder().create();
 * MyType target = new MyType();
 * String json = gson.toJson(target); // serializes target to JSON
 * MyType target2 = gson.fromJson(json, MyType.class); // deserializes json into target2
 * </pre>
 *
 * <p>If the type of the object that you are converting is a {@code ParameterizedType} (i.e. has at
 * least one type argument, for example {@code List<MyType>}) then for deserialization you must use
 * a {@code fromJson} method with {@link Type} or {@link TypeToken} parameter to specify the
 * parameterized type. For serialization specifying a {@code Type} or {@code TypeToken} is optional,
 * otherwise Gson will use the runtime type of the object. {@link TypeToken} is a class provided by
 * Gson which helps creating parameterized types. Here is an example showing how this can be done:
 *
 * <pre>
 * TypeToken&lt;List&lt;MyType&gt;&gt; listType = new TypeToken&lt;List&lt;MyType&gt;&gt;() {};
 * List&lt;MyType&gt; target = new LinkedList&lt;MyType&gt;();
 * target.add(new MyType(1, "abc"));
 *
 * Gson gson = new Gson();
 * String json = gson.toJson(target, listType.getType());
 * List&lt;MyType&gt; target2 = gson.fromJson(json, listType);
 * </pre>
 *
 * <p>See the <a href="https://github.com/google/gson/blob/main/UserGuide.md">Gson User Guide</a>
 * for a more complete set of examples.
 *
 * <h2 id="default-lenient">JSON Strictness handling</h2>
 *
 * For legacy reasons most of the {@code Gson} methods allow JSON data which does not comply with
 * the JSON specification when no explicit {@linkplain Strictness strictness} is set (the default).
 * To specify the strictness of a {@code Gson} instance, you should set it through {@link
 * GsonBuilder#setStrictness(Strictness)}.
 *
 * @see TypeToken
 * @author Inderjeet Singh
 * @author Joel Leitch
 * @author Jesse Wilson
 */
public final class Gson {

  // -------------------------------------------------------------------------
  // 1. Constantes
  // -------------------------------------------------------------------------

  private static final String JSON_NON_EXECUTABLE_PREFIX = ")]}\'\n";

  // -------------------------------------------------------------------------
  // 2. Attributs d'instance
  // -------------------------------------------------------------------------

  /**
   * Délègue toute la gestion du cache (ConcurrentHashMap partagée + ThreadLocal
   * anti-récursion + FutureTypeAdapter) à {@link TypeAdapterCache}.
   * Gson ne connaît plus les détails d'implémentation du cache.
   */
  private final TypeAdapterCache adapterCache = new TypeAdapterCache();

  private final ConstructorConstructor constructorConstructor;
  private final JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory;

  final List<TypeAdapterFactory> factories;

  final Excluder excluder;
  final FieldNamingStrategy fieldNamingStrategy;
  final Map<Type, InstanceCreator<?>> instanceCreators;
  final boolean serializeNulls;
  final boolean complexMapKeySerialization;
  final boolean generateNonExecutableJson;
  final boolean htmlSafe;
  final FormattingStyle formattingStyle;
  final Strictness strictness;
  final boolean serializeSpecialFloatingPointValues;
  final boolean useJdkUnsafe;
  final String datePattern;
  final int dateStyle;
  final int timeStyle;
  final LongSerializationPolicy longSerializationPolicy;
  final List<TypeAdapterFactory> builderFactories;
  final List<TypeAdapterFactory> builderHierarchyFactories;
  final ToNumberStrategy objectToNumberStrategy;
  final ToNumberStrategy numberToNumberStrategy;
  final List<ReflectionAccessFilter> reflectionFilters;

  // -------------------------------------------------------------------------
  // 3. Constructeurs
  // -------------------------------------------------------------------------

  /**
   * Constructs a Gson object with default configuration. The default configuration has the
   * following settings:
   *
   * <ul>
   *   <li>The JSON generated by {@code toJson} methods is in compact representation. This means
   *       that all the unneeded white-space is removed. You can change this behavior with {@link
   *       GsonBuilder#setPrettyPrinting()}.
   *   <li>The generated JSON omits all the fields that are null. Note that nulls in arrays are kept
   *       as is since an array is an ordered list. Moreover, if a field is not null, but its
   *       generated JSON is empty, the field is kept. You can configure Gson to serialize null
   *       values by setting {@link GsonBuilder#serializeNulls()}.
   *   <li>Gson provides default serialization and deserialization for Enums, {@link Map}, {@link
   *       java.net.URL}, {@link java.net.URI}, {@link java.util.Locale}, {@link java.util.Date},
   *       {@link java.math.BigDecimal}, and {@link java.math.BigInteger} classes. If you would
   *       prefer to change the default representation, you can do so by registering a type adapter
   *       through {@link GsonBuilder#registerTypeAdapter(Type, Object)}.
   *   <li>The default Date format is same as {@link java.text.DateFormat#DEFAULT}. This format
   *       ignores the millisecond portion of the date during serialization. You can change this by
   *       invoking {@link GsonBuilder#setDateFormat(int, int)} or {@link
   *       GsonBuilder#setDateFormat(String)}.
   *   <li>By default, Gson ignores the {@link com.google.gson.annotations.Expose} annotation. You
   *       can enable Gson to serialize/deserialize only those fields marked with this annotation
   *       through {@link GsonBuilder#excludeFieldsWithoutExposeAnnotation()}.
   *   <li>By default, Gson ignores the {@link com.google.gson.annotations.Since} annotation. You
   *       can enable Gson to use this annotation through {@link GsonBuilder#setVersion(double)}.
   *   <li>The default field naming policy for the output JSON is same as in Java. So, a Java class
   *       field {@code versionNumber} will be output as {@code "versionNumber"} in JSON. The same
   *       rules are applied for mapping incoming JSON to the Java classes. You can change this
   *       policy through {@link GsonBuilder#setFieldNamingPolicy(FieldNamingPolicy)}.
   *   <li>By default, Gson excludes {@code transient} or {@code static} fields from consideration
   *       for serialization and deserialization. You can change this behavior through {@link
   *       GsonBuilder#excludeFieldsWithModifiers(int...)}.
   *   <li>No explicit strictness is set. You can change this by calling {@link
   *       GsonBuilder#setStrictness(Strictness)}.
   * </ul>
   */
  public Gson() {
    this(GsonBuilder.DEFAULT);
  }

  Gson(GsonBuilder builder) {
    this.excluder = builder.excluder;
    this.fieldNamingStrategy = builder.fieldNamingPolicy;
    this.instanceCreators = new HashMap<>(builder.instanceCreators);
    this.serializeNulls = builder.serializeNulls;
    this.complexMapKeySerialization = builder.complexMapKeySerialization;
    this.generateNonExecutableJson = builder.generateNonExecutableJson;
    this.htmlSafe = builder.escapeHtmlChars;
    this.formattingStyle = builder.formattingStyle;
    this.strictness = builder.strictness;
    this.serializeSpecialFloatingPointValues = builder.serializeSpecialFloatingPointValues;
    this.useJdkUnsafe = builder.useJdkUnsafe;
    this.longSerializationPolicy = builder.longSerializationPolicy;
    this.datePattern = builder.datePattern;
    this.dateStyle = builder.dateStyle;
    this.timeStyle = builder.timeStyle;
    this.builderFactories = newImmutableList(builder.factories);
    this.builderHierarchyFactories = newImmutableList(builder.hierarchyFactories);
    this.objectToNumberStrategy = builder.objectToNumberStrategy;
    this.numberToNumberStrategy = builder.numberToNumberStrategy;
    this.reflectionFilters = newImmutableList(builder.reflectionFilters);
    if (builder == GsonBuilder.DEFAULT) {
      this.constructorConstructor = GsonBuilder.DEFAULT_CONSTRUCTOR_CONSTRUCTOR;
      this.jsonAdapterFactory = GsonBuilder.DEFAULT_JSON_ADAPTER_ANNOTATION_TYPE_ADAPTER_FACTORY;
      this.factories = GsonBuilder.DEFAULT_TYPE_ADAPTER_FACTORIES;
    } else {
      this.constructorConstructor =
              new ConstructorConstructor(instanceCreators, useJdkUnsafe, reflectionFilters);
      this.jsonAdapterFactory = new JsonAdapterAnnotationTypeAdapterFactory(constructorConstructor);
      this.factories = builder.createFactories(constructorConstructor, jsonAdapterFactory);
    }
  }

  // -------------------------------------------------------------------------
  // 4. Méthodes publiques
  // -------------------------------------------------------------------------

  /**
   * Returns a new GsonBuilder containing all custom factories and configuration used by the current
   * instance.
   *
   * @return a GsonBuilder instance.
   * @since 2.8.3
   */
  public GsonBuilder newBuilder() {
    return new GsonBuilder(this);
  }

  /**
   * @deprecated This method by accident exposes an internal Gson class; it might be removed in a
   *     future version.
   */
  @Deprecated
  public Excluder excluder() {
    return excluder;
  }

  /**
   * Returns the field naming strategy used by this Gson instance.
   *
   * @see GsonBuilder#setFieldNamingStrategy(FieldNamingStrategy)
   */
  public FieldNamingStrategy fieldNamingStrategy() {
    return fieldNamingStrategy;
  }

  /**
   * Returns whether this Gson instance is serializing JSON object properties with {@code null}
   * values, or just omits them.
   *
   * @see GsonBuilder#serializeNulls()
   */
  public boolean serializeNulls() {
    return serializeNulls;
  }

  /**
   * Returns whether this Gson instance produces JSON output which is HTML-safe, that means all HTML
   * characters are escaped.
   *
   * @see GsonBuilder#disableHtmlEscaping()
   */
  public boolean htmlSafe() {
    return htmlSafe;
  }

  /**
   * Returns the type adapter for {@code type}.
   *
   * <p>When calling this method concurrently from multiple threads and requesting an adapter for
   * the same type this method may return different {@code TypeAdapter} instances. However, that
   * should normally not be an issue because {@code TypeAdapter} implementations are supposed to be
   * stateless.
   *
   * @throws IllegalArgumentException if this Gson instance cannot serialize and deserialize {@code
   *     type}.
   */
  public <T> TypeAdapter<T> getAdapter(TypeToken<T> type) {
    Objects.requireNonNull(type, "type must not be null");

    // Fast path: already cached (shared cache ou thread-local en cours de résolution).
    TypeAdapter<T> cached = adapterCache.get(type);
    if (cached != null) {
      return cached;
    }

    // Slow path: résolution via les factories, avec gestion des dépendances cycliques.
    return adapterCache.resolve(type, factories, this);
  }

  /**
   * Returns the type adapter for {@code type}.
   *
   * @throws IllegalArgumentException if this Gson instance cannot serialize and deserialize {@code
   *     type}.
   */
  public <T> TypeAdapter<T> getAdapter(Class<T> type) {
    return getAdapter(TypeToken.get(type));
  }

  /**
   * This method is used to get an alternate type adapter for the specified type. This is used to
   * access a type adapter that is overridden by a {@link TypeAdapterFactory} that you may have
   * registered. This feature is typically used when you want to register a type adapter that does a
   * little bit of work but then delegates further processing to the Gson default type adapter.
   *
   * @param skipPast The type adapter factory that needs to be skipped while searching for a
   *     matching type adapter. In most cases, you should just pass <i>this</i> (the type adapter
   *     factory from where {@code getDelegateAdapter} method is being invoked).
   * @param type Type for which the delegate adapter is being searched for.
   * @since 2.2
   */
  public <T> TypeAdapter<T> getDelegateAdapter(TypeAdapterFactory skipPast, TypeToken<T> type) {
    Objects.requireNonNull(skipPast, "skipPast must not be null");
    Objects.requireNonNull(type, "type must not be null");

    if (jsonAdapterFactory.isClassJsonAdapterFactory(type, skipPast)) {
      skipPast = jsonAdapterFactory;
    }

    boolean skipPastFound = false;
    for (TypeAdapterFactory factory : factories) {
      if (!skipPastFound) {
        if (factory == skipPast) {
          skipPastFound = true;
        }
        continue;
      }
      TypeAdapter<T> candidate = factory.create(this, type);
      if (candidate != null) {
        return candidate;
      }
    }

    if (skipPastFound) {
      throw new IllegalArgumentException("GSON cannot serialize or deserialize " + type);
    } else {
      // Probably a factory from @JsonAdapter on a field
      return getAdapter(type);
    }
  }

  /**
   * This method serializes the specified object into its equivalent representation as a tree of
   * {@link JsonElement}s. This method should be used when the specified object is not a generic
   * type.
   *
   * @param src the object for which JSON representation is to be created
   * @return JSON representation of {@code src}.
   * @since 1.4
   * @see #toJsonTree(Object, Type)
   */
  public JsonElement toJsonTree(Object src) {
    if (src == null) {
      return JsonNull.INSTANCE;
    }
    return toJsonTree(src, src.getClass());
  }

  /**
   * This method serializes the specified object, including those of generic types, into its
   * equivalent representation as a tree of {@link JsonElement}s.
   *
   * @param src       the object for which JSON representation is to be created
   * @param typeOfSrc The specific genericized type of src.
   * @return JSON representation of {@code src}.
   * @since 1.4
   * @see #toJsonTree(Object)
   */
  public JsonElement toJsonTree(Object src, Type typeOfSrc) {
    JsonTreeWriter writer = new JsonTreeWriter();
    toJson(src, typeOfSrc, writer);
    return writer.get();
  }

  /**
   * This method serializes the specified object into its equivalent JSON representation. This
   * method should be used when the specified object is not a generic type.
   *
   * @param src the object for which JSON representation is to be created
   * @return JSON representation of {@code src}.
   * @see #toJson(Object, Appendable)
   * @see #toJson(Object, Type)
   */
  public String toJson(Object src) {
    if (src == null) {
      return toJson(JsonNull.INSTANCE);
    }
    return toJson(src, src.getClass());
  }

  /**
   * This method serializes the specified object, including those of generic types, into its
   * equivalent JSON representation.
   *
   * @param src       the object for which JSON representation is to be created
   * @param typeOfSrc The specific genericized type of src.
   * @return JSON representation of {@code src}.
   * @see #toJson(Object, Type, Appendable)
   * @see #toJson(Object)
   */
  public String toJson(Object src, Type typeOfSrc) {
    StringBuilder writer = new StringBuilder();
    toJson(src, typeOfSrc, writer);
    return writer.toString();
  }

  /**
   * This method serializes the specified object into its equivalent JSON representation and writes
   * it to the writer. This method should be used when the specified object is not a generic type.
   *
   * @param src    the object for which JSON representation is to be created
   * @param writer Writer to which the JSON representation needs to be written
   * @throws JsonIOException if there was a problem writing to the writer
   * @since 1.2
   * @see #toJson(Object)
   * @see #toJson(Object, Type, Appendable)
   */
  public void toJson(Object src, Appendable writer) throws JsonIOException {
    if (src != null) {
      toJson(src, src.getClass(), writer);
    } else {
      toJson(JsonNull.INSTANCE, writer);
    }
  }

  /**
   * This method serializes the specified object, including those of generic types, into its
   * equivalent JSON representation and writes it to the writer.
   *
   * @param src       the object for which JSON representation is to be created
   * @param typeOfSrc The specific genericized type of src.
   * @param writer    Writer to which the JSON representation of src needs to be written
   * @throws JsonIOException if there was a problem writing to the writer
   * @since 1.2
   * @see #toJson(Object, Type)
   * @see #toJson(Object, Appendable)
   */
  public void toJson(Object src, Type typeOfSrc, Appendable writer) throws JsonIOException {
    try {
      JsonWriter jsonWriter = newJsonWriter(Streams.writerForAppendable(writer));
      toJson(src, typeOfSrc, jsonWriter);
    } catch (IOException e) {
      throw new JsonIOException(e);
    }
  }

  /**
   * Writes the JSON representation of {@code src} of type {@code typeOfSrc} to {@code writer}.
   *
   * @param src       the object for which JSON representation is to be created
   * @param typeOfSrc the type of the object to be written
   * @param writer    Writer to which the JSON representation of src needs to be written
   * @throws JsonIOException if there was a problem writing to the writer
   */
  public void toJson(Object src, Type typeOfSrc, JsonWriter writer) throws JsonIOException {
    @SuppressWarnings("unchecked")
    TypeAdapter<Object> adapter = (TypeAdapter<Object>) getAdapter(TypeToken.get(typeOfSrc));

    Strictness oldStrictness = writer.getStrictness();
    if (this.strictness != null) {
      writer.setStrictness(this.strictness);
    } else if (writer.getStrictness() == Strictness.LEGACY_STRICT) {
      writer.setStrictness(Strictness.LENIENT);
    }

    boolean oldHtmlSafe = writer.isHtmlSafe();
    boolean oldSerializeNulls = writer.getSerializeNulls();
    writer.setHtmlSafe(htmlSafe);
    writer.setSerializeNulls(serializeNulls);
    try {
      adapter.write(writer, src);
    } catch (IOException e) {
      throw new JsonIOException(e);
    } catch (AssertionError e) {
      throw new AssertionError(
              "AssertionError (GSON " + GsonBuildConfig.VERSION + "): " + e.getMessage(), e);
    } finally {
      writer.setStrictness(oldStrictness);
      writer.setHtmlSafe(oldHtmlSafe);
      writer.setSerializeNulls(oldSerializeNulls);
    }
  }

  /**
   * Converts a tree of {@link JsonElement}s into its equivalent JSON representation.
   *
   * @param jsonElement root of a tree of {@link JsonElement}s
   * @return JSON String representation of the tree.
   * @since 1.4
   */
  public String toJson(JsonElement jsonElement) {
    StringBuilder writer = new StringBuilder();
    toJson(jsonElement, writer);
    return writer.toString();
  }

  /**
   * Writes out the equivalent JSON for a tree of {@link JsonElement}s.
   *
   * @param jsonElement root of a tree of {@link JsonElement}s
   * @param writer      Writer to which the JSON representation needs to be written
   * @throws JsonIOException if there was a problem writing to the writer
   * @since 1.4
   */
  public void toJson(JsonElement jsonElement, Appendable writer) throws JsonIOException {
    try {
      JsonWriter jsonWriter = newJsonWriter(Streams.writerForAppendable(writer));
      toJson(jsonElement, jsonWriter);
    } catch (IOException e) {
      throw new JsonIOException(e);
    }
  }

  /**
   * Writes the JSON for {@code jsonElement} to {@code writer}.
   *
   * @param jsonElement the JSON element to be written
   * @param writer      the JSON writer to which the provided element will be written
   * @throws JsonIOException if there was a problem writing to the writer
   */
  public void toJson(JsonElement jsonElement, JsonWriter writer) throws JsonIOException {
    Strictness oldStrictness = writer.getStrictness();
    boolean oldHtmlSafe = writer.isHtmlSafe();
    boolean oldSerializeNulls = writer.getSerializeNulls();
    writer.setHtmlSafe(htmlSafe);
    writer.setSerializeNulls(serializeNulls);
    if (this.strictness != null) {
      writer.setStrictness(this.strictness);
    } else if (writer.getStrictness() == Strictness.LEGACY_STRICT) {
      writer.setStrictness(Strictness.LENIENT);
    }
    try {
      Streams.write(jsonElement, writer);
    } catch (IOException e) {
      throw new JsonIOException(e);
    } catch (AssertionError e) {
      throw new AssertionError(
              "AssertionError (GSON " + GsonBuildConfig.VERSION + "): " + e.getMessage(), e);
    } finally {
      writer.setStrictness(oldStrictness);
      writer.setHtmlSafe(oldHtmlSafe);
      writer.setSerializeNulls(oldSerializeNulls);
    }
  }

  /**
   * Returns a new JSON writer configured for the settings on this Gson instance.
   *
   * <p>The following settings are considered: {@link GsonBuilder#disableHtmlEscaping()}, {@link
   * GsonBuilder#generateNonExecutableJson()}, {@link GsonBuilder#serializeNulls()}, {@link
   * GsonBuilder#setStrictness(Strictness)}, {@link GsonBuilder#setPrettyPrinting()}, and {@link
   * GsonBuilder#setFormattingStyle(FormattingStyle)}.
   */
  public JsonWriter newJsonWriter(Writer writer) throws IOException {
    if (generateNonExecutableJson) {
      writer.write(JSON_NON_EXECUTABLE_PREFIX);
    }
    JsonWriter jsonWriter = new JsonWriter(writer);
    jsonWriter.setFormattingStyle(formattingStyle);
    jsonWriter.setHtmlSafe(htmlSafe);
    jsonWriter.setStrictness(strictness == null ? Strictness.LEGACY_STRICT : strictness);
    jsonWriter.setSerializeNulls(serializeNulls);
    return jsonWriter;
  }

  /**
   * Returns a new JSON reader configured for the settings on this Gson instance.
   */
  public JsonReader newJsonReader(Reader reader) {
    JsonReader jsonReader = new JsonReader(reader);
    jsonReader.setStrictness(strictness == null ? Strictness.LEGACY_STRICT : strictness);
    return jsonReader;
  }

  /**
   * This method deserializes the specified JSON into an object of the specified class.
   *
   * @param <T>      the type of the desired object
   * @param json     the string from which the object is to be deserialized
   * @param classOfT the class of T
   * @return an object of type T from the string. Returns {@code null} if {@code json} is {@code
   *     null} or if {@code json} is empty.
   * @throws JsonSyntaxException if json is not a valid representation for an object of type classOfT
   * @see #fromJson(Reader, Class)
   * @see #fromJson(String, TypeToken)
   */
  public <T> T fromJson(String json, Class<T> classOfT) throws JsonSyntaxException {
    return fromJson(json, TypeToken.get(classOfT));
  }

  /**
   * This method deserializes the specified JSON into an object of the specified type.
   *
   * @param <T>     the type of the desired object
   * @param json    the string from which the object is to be deserialized
   * @param typeOfT The specific genericized type of src
   * @return an object of type T from the string. Returns {@code null} if {@code json} is {@code
   *     null} or if {@code json} is empty.
   * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
   * @see #fromJson(Reader, Type)
   * @see #fromJson(String, Class)
   * @see #fromJson(String, TypeToken)
   */
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  public <T> T fromJson(String json, Type typeOfT) throws JsonSyntaxException {
    return (T) fromJson(json, TypeToken.get(typeOfT));
  }

  /**
   * This method deserializes the specified JSON into an object of the specified type.
   *
   * @param <T>     the type of the desired object
   * @param json    the string from which the object is to be deserialized
   * @param typeOfT The specific genericized type of src.
   * @return an object of type T from the string. Returns {@code null} if {@code json} is {@code
   *     null} or if {@code json} is empty.
   * @throws JsonSyntaxException if json is not a valid representation for an object of the type typeOfT
   * @see #fromJson(Reader, TypeToken)
   * @see #fromJson(String, Class)
   * @since 2.10
   */
  public <T> T fromJson(String json, TypeToken<T> typeOfT) throws JsonSyntaxException {
    if (json == null) {
      return null;
    }
    return fromJson(new StringReader(json), typeOfT);
  }

  /**
   * This method deserializes the JSON read from the specified reader into an object of the
   * specified class.
   *
   * @param <T>      the type of the desired object
   * @param json     the reader producing the JSON from which the object is to be deserialized
   * @param classOfT the class of T
   * @return an object of type T from the Reader. Returns {@code null} if {@code json} is at EOF.
   * @throws JsonIOException     if there was a problem reading from the Reader
   * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
   * @since 1.2
   * @see #fromJson(String, Class)
   * @see #fromJson(Reader, TypeToken)
   */
  public <T> T fromJson(Reader json, Class<T> classOfT)
          throws JsonSyntaxException, JsonIOException {
    return fromJson(json, TypeToken.get(classOfT));
  }

  /**
   * This method deserializes the JSON read from the specified reader into an object of the
   * specified type.
   *
   * @param <T>     the type of the desired object
   * @param json    the reader producing JSON from which the object is to be deserialized
   * @param typeOfT The specific genericized type of src
   * @return an object of type T from the Reader. Returns {@code null} if {@code json} is at EOF.
   * @throws JsonIOException     if there was a problem reading from the Reader
   * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
   * @since 1.2
   * @see #fromJson(String, Type)
   * @see #fromJson(Reader, Class)
   * @see #fromJson(Reader, TypeToken)
   */
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  public <T> T fromJson(Reader json, Type typeOfT) throws JsonIOException, JsonSyntaxException {
    return (T) fromJson(json, TypeToken.get(typeOfT));
  }

  /**
   * This method deserializes the JSON read from the specified reader into an object of the
   * specified type.
   *
   * @param <T>     the type of the desired object
   * @param json    the reader producing JSON from which the object is to be deserialized
   * @param typeOfT The specific genericized type of src.
   * @return an object of type T from the Reader. Returns {@code null} if {@code json} is at EOF.
   * @throws JsonIOException     if there was a problem reading from the Reader
   * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
   * @since 2.10
   * @see #fromJson(String, TypeToken)
   * @see #fromJson(Reader, Class)
   */
  public <T> T fromJson(Reader json, TypeToken<T> typeOfT)
          throws JsonIOException, JsonSyntaxException {
    JsonReader jsonReader = newJsonReader(json);
    T object = fromJson(jsonReader, typeOfT);
    assertFullConsumption(object, jsonReader);
    return object;
  }

  // fromJson(JsonReader, Class) is unfortunately missing and cannot be added now without breaking
  // source compatibility in certain cases, see
  // https://github.com/google/gson/pull/1700#discussion_r973764414

  /**
   * Reads the next JSON value from {@code reader} and converts it to an object of type {@code
   * typeOfT}. Returns {@code null} if the {@code reader} is at EOF.
   *
   * <p>Unlike the other {@code fromJson} methods, no exception is thrown if the JSON data has
   * multiple top-level JSON elements, or if there is trailing data.
   *
   * @param <T>     the type of the desired object
   * @param reader  the reader whose next JSON value should be deserialized
   * @param typeOfT The specific genericized type of src
   * @return an object of type T from the JsonReader. Returns {@code null} if {@code reader} is at EOF.
   * @throws JsonIOException     if there was a problem reading from the JsonReader
   * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
   * @see #fromJson(Reader, Type)
   * @see #fromJson(JsonReader, TypeToken)
   */
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  public <T> T fromJson(JsonReader reader, Type typeOfT)
          throws JsonIOException, JsonSyntaxException {
    return (T) fromJson(reader, TypeToken.get(typeOfT));
  }

  /**
   * Reads the next JSON value from {@code reader} and converts it to an object of type {@code
   * typeOfT}. Returns {@code null} if the {@code reader} is at EOF.
   *
   * <p>Unlike the other {@code fromJson} methods, no exception is thrown if the JSON data has
   * multiple top-level JSON elements, or if there is trailing data.
   *
   * @param <T>     the type of the desired object
   * @param reader  the reader whose next JSON value should be deserialized
   * @param typeOfT The specific genericized type of src.
   * @return an object of type T from the JsonReader. Returns {@code null} if {@code reader} is at EOF.
   * @throws JsonIOException     if there was a problem reading from the JsonReader
   * @throws JsonSyntaxException if json is not a valid representation for an object of the type typeOfT
   * @since 2.10
   * @see #fromJson(Reader, TypeToken)
   * @see #fromJson(JsonReader, Type)
   */
  public <T> T fromJson(JsonReader reader, TypeToken<T> typeOfT)
          throws JsonIOException, JsonSyntaxException {
    boolean isEmpty = true;
    Strictness oldStrictness = reader.getStrictness();
    if (this.strictness != null) {
      reader.setStrictness(this.strictness);
    } else if (reader.getStrictness() == Strictness.LEGACY_STRICT) {
      reader.setStrictness(Strictness.LENIENT);
    }
    try {
      JsonToken unused = reader.peek();
      isEmpty = false;
      TypeAdapter<T> typeAdapter = getAdapter(typeOfT);
      T object = typeAdapter.read(reader);
      Class<?> expectedTypeWrapped = Primitives.wrap(typeOfT.getRawType());
      if (object != null && !expectedTypeWrapped.isInstance(object)) {
        throw new ClassCastException(
                "Type adapter '"
                        + typeAdapter
                        + "' returned wrong type; requested "
                        + typeOfT.getRawType()
                        + " but got instance of "
                        + object.getClass()
                        + "\nVerify that the adapter was registered for the correct type.");
      }
      return object;
    } catch (EOFException e) {
      if (isEmpty) {
        return null;
      }
      throw new JsonSyntaxException(e);
    } catch (IllegalStateException e) {
      throw new JsonSyntaxException(e);
    } catch (IOException e) {
      // TODO(inder): Figure out whether it is indeed right to rethrow this as JsonSyntaxException
      throw new JsonSyntaxException(e);
    } catch (AssertionError e) {
      throw new AssertionError(
              "AssertionError (GSON " + GsonBuildConfig.VERSION + "): " + e.getMessage(), e);
    } finally {
      reader.setStrictness(oldStrictness);
    }
  }

  /**
   * This method deserializes the JSON read from the specified parse tree into an object of the
   * specified type. It is not suitable to use if the specified class is a generic type.
   *
   * @param <T>      the type of the desired object
   * @param json     the root of the parse tree of {@link JsonElement}s from which the object is to
   *                 be deserialized
   * @param classOfT The class of T
   * @return an object of type T from the JSON. Returns {@code null} if {@code json} is {@code null}
   *     or if {@code json} is empty.
   * @throws JsonSyntaxException if json is not a valid representation for an object of type classOfT
   * @since 1.3
   * @see #fromJson(Reader, Class)
   * @see #fromJson(JsonElement, TypeToken)
   */
  public <T> T fromJson(JsonElement json, Class<T> classOfT) throws JsonSyntaxException {
    return fromJson(json, TypeToken.get(classOfT));
  }

  /**
   * This method deserializes the JSON read from the specified parse tree into an object of the
   * specified type. This method is useful if the specified object is a generic type.
   *
   * @param <T>     the type of the desired object
   * @param json    the root of the parse tree of {@link JsonElement}s from which the object is to
   *                be deserialized
   * @param typeOfT The specific genericized type of src
   * @return an object of type T from the JSON. Returns {@code null} if {@code json} is {@code null}
   *     or if {@code json} is empty.
   * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
   * @since 1.3
   * @see #fromJson(Reader, Type)
   * @see #fromJson(JsonElement, Class)
   * @see #fromJson(JsonElement, TypeToken)
   */
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  public <T> T fromJson(JsonElement json, Type typeOfT) throws JsonSyntaxException {
    return (T) fromJson(json, TypeToken.get(typeOfT));
  }

  /**
   * This method deserializes the JSON read from the specified parse tree into an object of the
   * specified type. This method is useful if the specified object is a generic type.
   *
   * @param <T>     the type of the desired object
   * @param json    the root of the parse tree of {@link JsonElement}s from which the object is to
   *                be deserialized
   * @param typeOfT The specific genericized type of src.
   * @return an object of type T from the JSON. Returns {@code null} if {@code json} is {@code null}
   *     or if {@code json} is empty.
   * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
   * @since 2.10
   * @see #fromJson(Reader, TypeToken)
   * @see #fromJson(JsonElement, Class)
   */
  public <T> T fromJson(JsonElement json, TypeToken<T> typeOfT) throws JsonSyntaxException {
    if (json == null) {
      return null;
    }
    return fromJson(new JsonTreeReader(json), typeOfT);
  }

  @Override
  public String toString() {
    return "{serializeNulls:"
            + serializeNulls
            + ",factories:"
            + factories
            + ",instanceCreators:"
            + constructorConstructor
            + "}";
  }

  // -------------------------------------------------------------------------
  // 5. Méthodes privées / package-private
  // -------------------------------------------------------------------------

  private static void assertFullConsumption(Object obj, JsonReader reader) {
    try {
      if (obj != null && reader.peek() != JsonToken.END_DOCUMENT) {
        throw new JsonSyntaxException("JSON document was not fully consumed.");
      }
    } catch (MalformedJsonException e) {
      throw new JsonSyntaxException(e);
    } catch (IOException e) {
      throw new JsonIOException(e);
    }
  }
}