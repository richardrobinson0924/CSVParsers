//
// Created by Richard I. Robinson on 5/3/2020.
//

#ifndef CSVREADER_H
#define CSVREADER_H

#include <istream>
#include <sstream>

template<typename A, typename B>
concept SameAs = std::is_same_v<A, B>;

/// A type T is Parseable iff `operator>>` is implemented for T
template<typename T>
concept Parseable = requires(std::istream &is, T& t) {
    {is >> t} -> SameAs<decltype(is)>;
};

template<Parseable... Fields>
class CSVReader {
public:
    using value_type = std::tuple<Fields...>;

    class iterator {
    public:
        /// Default constructor. Creates an empty iterator.
        iterator() : reader(nullptr){};

        /// Creates a new iterator to iterate over the given CSVReader
        /// \param reader the CSVReader to iterate over
        explicit iterator(CSVReader<Fields...> &reader) : reader(&reader) {
            operator++();
        }

        /// Goes to the next row if possible.
        /// If the stream is at EOF prior, throws std::out_of_range
        /// \return this iterator
        /// \throw std::out_of_range, if attempting to go to the next row after EOF
        iterator operator++() {
            if (reader == nullptr) throw std::out_of_range("EOF");

            if (!reader->has_next_row()) {
                reader = nullptr;
            } else {
                current_ = reader->next_row();
            }

            return *this;
        }

        /// Returns true if this iterator and `other` are not equal.
        /// Two iterators are equal iff their readers point to the same address in memory
        /// \param other the iterator to test against
        /// \return `true` if both this iterator and `other` have the same CSVReader, or are both nullptr
        bool operator!=(const iterator &other) const {
            return reader != other.reader;
        }

        /// Gets the current row, parsed as an std::tuple of Fields
        /// \return a std::tuple of Fields containing the parsed row
        value_type operator*() const {
            return current_;
        }

    private:
        value_type current_;
        CSVReader<Fields...> *reader;
    };

    /// Creates a new CSVReader instance from the given input stream, with an optionally
    /// specified delimiter
    /// \param is the input stream to read of
    /// \param sep the delimiter of fields in each row (by default, ',')
    explicit CSVReader(std::istream &is, const char sep = ',')
        : stream_(is), separator_(sep) {
    }

    /// Returns an iterator to the beginning of the CSV stream
    /// \return a CSVReader<Fields...>::iterator to the beginning of the CSV stream
    iterator begin() {
        return iterator{*this};
    }

    /// Returns an iterator to the CSV stream's EOF
    /// \return an iterator to the CSV stream's EOF
    iterator end() {
        return iterator();
    }

private:
    std::istream &stream_;
    const char separator_;

    template<Parseable T>
    T parse(std::stringstream &ss) const {
        T t;
        std::string tmp;

        std::getline(ss, tmp, separator_);
        std::stringstream(tmp) >> t;

        return t;
    }

    [[nodiscard]] bool has_next_row() const {
        return !stream_.eof();
    }

    value_type next_row() {
        if (!has_next_row()) throw std::out_of_range("EOF");

        std::string tmp;
        std::getline(stream_, tmp);
        auto ss = std::stringstream(tmp);

        return std::tuple<Fields...>(parse<Fields>(ss)...);
    }
};


#endif//CSVREADER_H
