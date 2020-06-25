import java.io.BufferedReader
import java.io.Reader
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

/**
 * This class allows CSV text files to be conveniently and easily parsed into a stream of objects of the specified type
 *
 * By default, CSVReader supports parsing `Integer, Double, String, Boolean` types. Parsers for other types may be added via [CSVParser.registerParser]
 *
 * **Usage**: Suppose we have the following class `Foo`:
 * ```kotlin
 * data class Foo(val s: String, @Serializable("value") val i: Float = -1.0)
 * ```
 * Given a [Reader] whose contents are
 * ```
 * s,value
 * hello, 3.14
 * world
 * ```
 * each line can be parsed into a `Foo` object using
 * ```kotlin
 * val csv = CSVReader.from<Foo>(reader)
 * CSVReader.registerParser(String::toFloat)
 * csv.useRows { it.forEach(::println) } // prints each Foo
 * ```
 *
 * @param Row a type which must satisfy the following properties:
 * - It is a _data class_
 * - For each non-optional value parameter, there is a header in the CSV text matching either the name of the parameter or the value specified for the parameter by the [Serializable] annotation
 * - The class and each value parameter are `public`
 * - The types of the value parameters of `Row` are a combination of the types which support parsing by default. Otherwise, a custom parser has been added via [CSVParser.registerParser] for each applicable type.
 *
 * @author Richard I Robinson
 */
class CSVParser<Row : Any> @PublishedApi internal constructor(
    @PublishedApi internal val reader: BufferedReader,
    private val cls: KClass<Row>,
    private val delimiter: String = ","
) {
    /**
     * This annotation may be applied to any value parameter of a data class to provide an alternate name for the parameter to be matched against the headers of the CSV text
     */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class Serializable(val value: String)

    private val headers = reader.readLine().split(delimiter)

    /**
     * Calls the [block] callback giving it a sequence of all the parsed [Row]s in this file and closes the reader once
     * the processing is complete.
     *
     * @return the value returned by [block].
     * @throws IllegalArgumentException if one or more of the following is true:
     * - [Row] is not a data class,
     * - The non-optional parameters of [Row] do not have names or annotated names corresponding to the names of the headers of the CSV text
     * - The types of the parameters of [Row] are not parsable by default, nor have had parsers registered for them
     */
    inline fun <T> useRows(block: (Sequence<Row>) -> T): T = reader.use {
        block(it.lineSequence().map(this@CSVParser::parseRow))
    }

    @Suppress("UNCHECKED_CAST")
    @PublishedApi internal fun parseRow(row: String): Row {
        val split = row.split(delimiter)
        require(cls.isData)

        val ctor = cls.primaryConstructor!!

        val parsedParams = ctor.parameters.associateWith {
            val name = it.findAnnotation<Serializable>()?.value ?: it.name

            val idx = headers.indexOf(name)
            require(idx != -1)
            if (idx !in split.indices) return@associateWith null

            require(it.type.javaType in ParserMap)
            ParserMap[it.type.javaType]!!.invoke(split[idx])
        }

        return ctor.callBy(parsedParams.filterValues { it != null })
    }

    companion object {
        @PublishedApi internal val ParserMap = mutableMapOf<Class<*>, (String) -> Any>(
            Int::class.java to String::toInt,
            Double::class.java to String::toDouble,
            String::class.java to { it },
            Boolean::class.java to String::toBoolean
        )

        /**
         * Globally registers a parser for [T], which may or may not be parsable by default
         * @param parser a function mapping a [String] to an arbitrary type [T]
         */
        inline fun <reified T : Any> registerParser(noinline parser: (String) -> T) {
            ParserMap[T::class.java] = parser
        }

        /**
         * Creates a new CSVReader<T> instance from the specified [reader] whose lines may be parsed into instances of [T]
         *
         * @param reader a [Reader] containing `n` lines of text, each line containing `m` fields separated by a [delimiter]
         * @param delimiter the delimiter to use
         */
        inline fun <reified T : Any> from(reader: Reader, delimiter: String = ","): CSVParser<T> {
            val br = if (reader is BufferedReader) reader else BufferedReader(reader)
            return CSVParser(br, T::class, delimiter)
        }
    }
}
