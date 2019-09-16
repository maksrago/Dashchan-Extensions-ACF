/*
The MIT License (MIT)

Copyright (c) 2015 Robin Stocker (@robinst), Anders (@MTDdk), Iurii Dorofeev (@otopba)
(C) 2019 @v1ne

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package com.sixsixthree.dashchan.chan.sixteenchan;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class UrlScanner implements Iterator<UrlScanner.Span> {
    private final CharSequence input;
    private final LinkIterator linkIterator;
    private int index = 0;
    private Span nextLink = null;

    public UrlScanner(CharSequence input) {
        this.input = input;
        this.linkIterator = new LinkIterator();
    }

    public static Iterable<Span> sliceInput(final CharSequence input) {
        return new Iterable<Span>() {
            @Override
            public Iterator<Span> iterator() {
                return new UrlScanner(input);
            }
        };
    }

    private static int findUrlEnd(CharSequence input, int beginIndex) {
        int round = 0;
        int square = 0;
        int curly = 0;
        boolean doubleQuote = false;
        boolean singleQuote = false;
        int last = -1;
        loop:
        for (int i = beginIndex; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\u0000':
                case '\u0001':
                case '\u0002':
                case '\u0003':
                case '\u0004':
                case '\u0005':
                case '\u0006':
                case '\u0007':
                case '\u0008':
                case '\t':
                case '\n':
                case '\u000B':
                case '\f':
                case '\r':
                case '\u000E':
                case '\u000F':
                case '\u0010':
                case '\u0011':
                case '\u0012':
                case '\u0013':
                case '\u0014':
                case '\u0015':
                case '\u0016':
                case '\u0017':
                case '\u0018':
                case '\u0019':
                case '\u001A':
                case '\u001B':
                case '\u001C':
                case '\u001D':
                case '\u001E':
                case '\u001F':
                    // These are part of "fragment percent-encode set" which means they need to be
                    // percent-encoded in an URL: https://url.spec.whatwg.org/#fragment-percent-encode-set
                case ' ':
                case '\"':
                case '<':
                case '>':
                case '`':
                case '\u007F':
                case '\u0080':
                case '\u0081':
                case '\u0082':
                case '\u0083':
                case '\u0084':
                case '\u0085':
                case '\u0086':
                case '\u0087':
                case '\u0088':
                case '\u0089':
                case '\u008A':
                case '\u008B':
                case '\u008C':
                case '\u008D':
                case '\u008E':
                case '\u008F':
                case '\u0090':
                case '\u0091':
                case '\u0092':
                case '\u0093':
                case '\u0094':
                case '\u0095':
                case '\u0096':
                case '\u0097':
                case '\u0098':
                case '\u0099':
                case '\u009A':
                case '\u009B':
                case '\u009C':
                case '\u009D':
                case '\u009E':
                case '\u009F':
                    // The above can never be part of an URL, so stop now. See RFC 3986 and RFC 3987.
                    // Some characters are not in the above list, even they are not in "unreserved" or "reserved":
                    //   '\\', '^', '{', '|', '}'
                    // The reason for this is that other link detectors also allow them. Also see below, we require
                    // the braces to be balanced.
                case '\u00A0': // no-break space
                case '\u2000': // en quad
                case '\u2001': // em quad
                case '\u2002': // en space
                case '\u2003': // em space
                case '\u2004': // three-per-em space
                case '\u2005': // four-per-em space
                case '\u2006': // six-per-em space
                case '\u2007': // figure space
                case '\u2008': // punctuation space
                case '\u2009': // thin space
                case '\u200A': // hair space
                case '\u2028': // line separator
                case '\u2029': // paragraph separator
                case '\u202F': // narrow no-break space
                case '\u205F': // medium mathematical space
                case '\u3000': // ideographic space
                    // While these are allowed by RFC 3987, they are Unicode whitespace characters
                    // that look like a space, so it would be confusing not to end URLs.
                    // They are also excluded from IDNs by some browsers.
                    break loop;
                case '?':
                case '!':
                case '.':
                case ',':
                case ':':
                case ';':
                    // These may be part of an URL but not at the end
                    break;
                case '/':
                    // This may be part of an URL and at the end, but not if the previous character can't be the end of an URL
                    if (last == i - 1) {
                        last = i;
                    }
                    break;
                case '(':
                    round++;
                    break;
                case ')':
                    round--;
                    if (round >= 0) {
                        last = i;
                    } else {
                        // More closing than opening brackets, stop now
                        break loop;
                    }
                    break;
                case '[':
                    // Allowed in IPv6 address host
                    square++;
                    break;
                case ']':
                    // Allowed in IPv6 address host
                    square--;
                    if (square >= 0) {
                        last = i;
                    } else {
                        // More closing than opening brackets, stop now
                        break loop;
                    }
                    break;
                case '{':
                    curly++;
                    break;
                case '}':
                    curly--;
                    if (curly >= 0) {
                        last = i;
                    } else {
                        // More closing than opening brackets, stop now
                        break loop;
                    }
                    break;
                case '\'':
                    singleQuote = !singleQuote;
                    if (!singleQuote) {
                        last = i;
                    }
                    break;
                default:
                    last = i;
            }
        }
        return last;
    }

    private static boolean isAlpha(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlnum(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private static boolean isNonAscii(char c) {
        return c >= 0x80;
    }

    private static boolean schemeSpecial(char c) {
        switch (c) {
            case '+':
            case '-':
            case '.':
                return true;
        }
        return false;
    }

    @Override
    public boolean hasNext() {
        return index < input.length();
    }

    private Span nextTextSpan(int endIndex) {
        Span span = new Span(Type.kText, index, endIndex);
        index = endIndex;
        return span;
    }

    @Override
    public Span next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        if (nextLink == null) {
            if (linkIterator.hasNext()) {
                nextLink = linkIterator.next();
            } else {
                return nextTextSpan(input.length());
            }
        }

        if (index < nextLink.beginIndex) {
            // text before link, return plain
            return nextTextSpan(nextLink.beginIndex);
        } else {
            // at link, return it and make sure we continue after it next time
            Span span = nextLink;
            index = nextLink.endIndex;
            nextLink = null;
            return span;
        }
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove");
    }

    enum Type {kText, kUrl}

    final class Span {
        final Type type;
        final int beginIndex;
        final int endIndex;

        Span(Type type, int beginIndex, int endIndex) {
            this.type = type;
            this.beginIndex = beginIndex;
            this.endIndex = endIndex;
        }
    }

    private class LinkIterator implements Iterator<Span> {
        private Span next = null;
        private int index = 0;
        private int rewindIndex = 0;

        @Override
        public boolean hasNext() {
            setNext();
            return next != null;
        }

        @Override
        public Span next() {
            if (hasNext()) {
                Span link = next;
                next = null;
                return link;
            } else {
                throw new NoSuchElementException();
            }
        }

        private void setNext() {
            if (next != null) {
                return;
            }

            int length = input.length();
            while (index < length) {
                if (trigger()) {
                    Span link = scan(input, index, rewindIndex);
                    if (link != null) {
                        next = link;
                        index = link.endIndex;
                        rewindIndex = index;
                        break;
                    } else {
                        index++;
                    }
                } else {
                    index++;
                }
            }
        }

        private boolean trigger() {
            if (input.charAt(index) == ':')
                return true;
            if (index - rewindIndex > 4)
                if (input.charAt(index) == ';') {
                    CharSequence subSequence = input.subSequence(index - 4, index + 1);
                    if (subSequence.equals("&#58;"))
                        return true;
                }
            return false;
        }

        private Span scan(CharSequence input, int triggerIndex, int rewindIndex) {
            int length = input.length();
            int afterSlashSlash = triggerIndex + 3;
            if (afterSlashSlash >= length || input.charAt(triggerIndex + 1) != '/' || input.charAt(triggerIndex + 2) != '/') {
                return null;
            }

            if(input.charAt(triggerIndex) == ';')
                triggerIndex -= 4;

            int first = findStart(input, triggerIndex - 1, rewindIndex);
            if (first == -1) {
                return null;
            }

            int last = findUrlEnd(input, afterSlashSlash);
            if (last == -1) {
                return null;
            }

            return new Span(Type.kUrl, first, last + 1);
        }

        // See "scheme" in RFC 3986
        private int findStart(CharSequence input, int beginIndex, int rewindIndex) {
            int first = -1;
            int digit = -1;
            for (int i = beginIndex; i >= rewindIndex; i--) {
                char c = input.charAt(i);
                if (isAlpha(c)) {
                    first = i;
                } else if (isDigit(c)) {
                    digit = i;
                } else if (!schemeSpecial(c)) {
                    break;
                }
            }
            if (first > 0 && first - 1 == digit) {
                // We don't want to extract "abc://foo" out of "1abc://foo".
                // ".abc://foo" and others are ok though, as they feel more like separators.
                first = -1;
            }
            return first;
        }
    }
}
