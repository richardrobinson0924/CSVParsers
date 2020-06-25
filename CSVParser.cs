using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using ParserDictionary = System.Collections.Generic.Dictionary<System.Type, System.Converter<string, object>>;

namespace CSV
{
    /// <inheritdoc />
    public sealed class ParseException : Exception
    {
        /// <inheritdoc />
        public ParseException()
        {
        }

        /// <inheritdoc />
        public ParseException(string message) : base(message)
        {
        }

        /// <inheritdoc />
        public ParseException(string message, Exception inner) : base(message, inner)
        {
        }
    }

    /// <summary>
    /// This attribute may be applied to any property of a class or struct to indicate that the custom name should
    /// be matched against the headers of the CSV file instead of the name of the attribute
    /// </summary>
    /// 
    /// <example>
    /// <c>[CSV.PropertyName("value")] public int Num { get; set; }</c>
    /// </example>
    [AttributeUsage(AttributeTargets.Property)]
    public sealed class PropertyNameAttribute : Attribute
    {
        /// <summary>
        /// The name of the property.
        /// </summary>
        public string Name { get; }

        /// <summary>
        /// Initializes a new instance of <see cref="PropertyNameAttribute"/> with the specified property name.
        /// </summary>
        /// <param name="name">The name of the property.</param>
        public PropertyNameAttribute(string name) => Name = name;
    }

    /// <summary>
    /// A struct for accessing the map of parsers used by <see cref="Parser{TRow}"/>
    /// </summary>
    public readonly struct Parsers
    {
        private static readonly ParserDictionary Dict = new ParserDictionary
        {
            {typeof(int), s => int.Parse(s)},
            {typeof(double), s => double.Parse(s)},
            {typeof(string), s => s},
            {typeof(bool), s => bool.Parse(s)}
        };

        /// <summary>
        /// Returns the <see cref="Converter{TInput,TOutput}"/> used to parse strings to objects of the specified type
        /// </summary>
        /// <param name="t">the Type to find the converter for</param>
        /// <returns>The <c>Converter</c> parser function associated with <c>t</c></returns>
        public static Converter<string, object> Get(Type t) => Dict[t];

        /// <summary>
        /// A compile-time variant of <see cref="Get"/>. Should be preferred when possible.
        /// </summary>
        /// <typeparam name="T">the Type to find the converter for</typeparam>
        /// <returns>The <c>Converter</c> parser function associated with <c>T</c></returns>
        public static Converter<string, T> Get<T>() => Dict[typeof(T)] as Converter<string, T>;

        /// <summary>
        /// Globally registers a parser for <typeparamref name="T"/>, overriding any parser which may exist for the same type
        /// </summary>
        /// <param name="parser">a <c>Converter</c> from a string to an arbitrary type <c>T</c></param>
        /// <typeparam name="T">a type to make available for parsing into</typeparam>
        public static void RegisterParser<T>(Converter<string, T> parser)
        {
            object CovarianceCaster(string s) => parser(s);
            Dict[typeof(T)] = CovarianceCaster;
        }
    }

    /// <summary>
    /// This class allows CSV text strings to be conveniently and easily parsed into an Enumerable sequence of objects of type <c>TRow</c>
    /// </summary>
    ///
    /// <para>
    /// By default, CSV.Parser supports parsing <c>int, double, string, boolean</c> types.
    /// Parsers for other types may be added via <see cref="Parsers.RegisterParser{T}(Converter{string,T})"/>.
    /// </para>
    ///
    /// <example>
    /// Suppose there exists the following struct <c>Foo</c>:
    /// <code>
    /// public struct Foo
    /// {
    ///     [CSV.PropertyName("Value")] public float X { get; set; }
    ///     public string Name { get; set; }
    /// }
    /// </code>
    /// Given a <see cref="TextReader"/> whose contents are
    /// <code>
    /// Name,Value
    /// hello,3.14
    /// world
    /// </code>
    /// each line can be parsed into a <c>Foo</c> object using
    /// <code>
    /// var csv = new CSV.Parser(reader)
    /// CSV.Parsers.RegisterParser(float.Parse)
    /// foreach (var foo in csv) Console.WriteLine(foo);
    /// </code>
    /// </example>
    /// 
    /// <typeparam name="TRow">
    /// a type that satisfies the following properties:
    /// <list type="bullet">
    ///     <item>It has a no-argument constructor (satisfies the <c>new()</c> constraint)</item>
    ///     <item>Any property which should be affected should have an accessor</item>
    /// </list>
    /// </typeparam>
    public class Parser<TRow> : IEnumerable<TRow> where TRow : new()
    {
        private readonly TextReader _reader;
        private readonly string _delimiter;
        private readonly List<string> _headers;

        /// <summary>
        /// Creates a new CSV.Parser instance from the specified <c>reader</c> whose lines may be parsed into <c>TRow</c> instances
        /// </summary>
        /// <param name="reader">a <c>TextReader</c> containing N lines of text, each line containing M data fields separated by a <c>delimiter</c></param>
        /// <param name="delimiter">the delimiter to use</param>
        public Parser(TextReader reader, string delimiter = ",")
        {
            _reader = reader;
            _delimiter = delimiter;
            _headers = _reader.ReadLine()?.Split(delimiter).ToList();
        }

        /// <summary>
        /// Parses the next line of the associated <see cref="TextReader"/> into a <c>TRow</c> object
        /// </summary>
        /// <returns>The parsed TRow object</returns>
        public TRow ReadLine()
        {
            var line = _reader.ReadLine();
            if (line == null) return default;

            var split = line.Split(_delimiter);
            object row = new TRow();

            foreach (var prop in typeof(TRow).GetProperties().Where(p => p.CanWrite))
            {
                var attr = prop.GetCustomAttribute<PropertyNameAttribute>();
                var name = attr == null ? prop.Name : attr.Name;

                var idx = _headers.IndexOf(name);
                if (idx >= split.Length) continue;

                try
                {
                    var parsed = idx == -1 ? null : Parsers.Get(prop.PropertyType).Invoke(split[idx]);
                    prop.SetValue(row, parsed);
                }
                catch (KeyNotFoundException)
                {
                    throw new ParseException($"There is no registered parser for {prop.PropertyType}");
                }
                catch (Exception e)
                {
                    throw new ParseException($"The parser for {prop.PropertyType} failed", e);
                }
            }

            return (TRow) row;
        }

        /// <summary>
        /// Returns an <see cref="IEnumerator{T}"/> by repeatedly invoking <see cref="Parser{TRow}.ReadLine()"/>.
        /// </summary>
        /// <returns>an <see cref="IEnumerator{T}"/> of all the parsed rows</returns>
        public IEnumerator<TRow> GetEnumerator()
        {
            for (var row = ReadLine(); !row.Equals(default(TRow)); row = ReadLine())
            {
                yield return row;
            }
        }

        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }
}
