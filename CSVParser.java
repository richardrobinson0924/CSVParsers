package com.richardrobinson;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Map.*;

/**
 * This class allows CSV text files to be conveniently and easily parsed into a stream of objects of the specified type
 * <p>
 * By default, CSVParser supports parsing {@code Integer, Double, String, Boolean} types. Parsers for other types may be added via {@link CSVReader#registerParser(Class, Function)}
 * <p>
 * <b>Example:</b>
 * Given the following class {@code Foo}:
 * <pre>{@code class Foo {
 *     public Integer value;
 *     @Serializable("alpha") public String s;
 * }}</pre>
 * <p>
 * and a {@link BufferedReader} whose contents are
 * <pre>{@code alpha,value
 * hello,42
 * world,100}</pre>
 * <p>
 * a CSVReader instance may be used as follows to parse the reader:
 * <pre>{@code final var csv = CSVReader.from(reader, Foo.class, ",");
 * final var rows = csv.rows(); // a Stream of Foos
 * }</pre>
 *
 * @param <T> the type of the objects to create. The names of the fields of the class of {@code T} must have a one-to-one correspondence with the names of the headers of the CSV text. Optionally, a field may be annotated with the {@link Serializable} annotation to provide an alternate name for the field to use when parsing.
 */
public class CSVParser<T> {
	/**
	 * This annotation may be applied to any field of {@code T} to provide an alternate name to match with instead of the name of the field.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD})
	public @interface Serializable {
		String value();
	}

	private final BufferedReader reader;
	private final Class<T> clazz;
	private final String delimiter;
	private final List<String> headers;

	private static final Map<Class<?>, Function<String, ?>> PARSERS = new HashMap<>(ofEntries(
			entry(Integer.class, Integer::parseInt),
			entry(Double.class, Double::parseDouble),
			entry(String.class, s -> s),
			entry(Boolean.class, Boolean::parseBoolean)
	));

	/**
	 * Enables support for a type {@code T} for CSVReader instances in addition to the types supported by default
	 *
	 * @param cls    the Class to add support for (for example, {@code Foo.class})
	 * @param parser a Function mapping a {@link String} to a {@code T}
	 * @param <T>    the type corresponding to {@code cls}
	 */
	public static <T> void registerParser(Class<T> cls, Function<String, T> parser) {
		PARSERS.put(cls, parser);
	}

	private CSVParser(BufferedReader reader, Class<T> clazz, String delimiter) throws IOException {
		this.reader = reader;
		this.clazz = clazz;
		this.delimiter = delimiter;
		this.headers = List.of(reader.readLine().split(delimiter));
	}

	/**
	 * Creates a new CSVParser instance from the specified {@code reader}, whose lines may be parsed into instances of type {@code cls}.
	 *
	 * @param reader    a {@link BufferedReader} containing {@code n} lines of text, with each line containing {@code m} fields separated by a delimiter.
	 * @param cls     the class of the type of object that each row is parsed into. For example, {@code Foo.class}
	 * @param delimiter the delimiter to use
	 * @param <T>       the type corresponding to {@code clazz}
	 * @return a new CSVReader instance
	 * @throws IOException if an I/O error occurs
	 */
	public static <T> CSVParser<T> from(BufferedReader reader, Class<T> cls, String delimiter) throws IOException {
		return new CSVParser<>(reader, cls, delimiter);
	}

	/**
	 * Maps each line of the reader to a parsed instance of type {@code T}. The number of fields per line must be no less than the number of fields of class {@code T}.
	 *
	 * @return a Stream of instances of type {@code T} corresponding to each line
	 */
	public Stream<T> rows() {
		return reader.lines().map(this::parseRow);
	}

	private T parseRow(String row) {
		final var split = row.split(delimiter);

		try {
			final var ctor = clazz.getDeclaredConstructor();
			final var inst = ctor.newInstance();

			for (final var field : clazz.getFields()) {
				final var annotation = field.getAnnotation(Serializable.class);
				final var name = annotation == null ? field.getName() : annotation.value();

				final var index = headers.indexOf(name);
				if (index == -1) throw new IllegalArgumentException();

				final var value = PARSERS.get(field.getType()).apply(split[index]);
				field.set(inst, value);
			}

			return inst;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}
}
