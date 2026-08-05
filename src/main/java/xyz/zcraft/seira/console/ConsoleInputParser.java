package xyz.zcraft.seira.console;

import java.util.ArrayList;
import java.util.List;

final class ConsoleInputParser {
    private ConsoleInputParser() {
    }

    static ParsedInput parse(String input) {
        String raw = input == null ? "" : input;
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < raw.length()) {
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) {
                index++;
            }
            if (index == raw.length()) {
                break;
            }

            int start = index;
            StringBuilder value = new StringBuilder();
            char quote = 0;
            while (index < raw.length()) {
                char current = raw.charAt(index);
                if (quote != 0) {
                    if (current == quote) {
                        quote = 0;
                        index++;
                    } else if (current == '\\' && index + 1 < raw.length()) {
                        value.append(raw.charAt(index + 1));
                        index += 2;
                    } else {
                        value.append(current);
                        index++;
                    }
                } else if (current == '\'' || current == '"') {
                    quote = current;
                    index++;
                } else if (Character.isWhitespace(current)) {
                    break;
                } else if (current == '\\' && index + 1 < raw.length()) {
                    value.append(raw.charAt(index + 1));
                    index += 2;
                } else {
                    value.append(current);
                    index++;
                }
            }
            if (quote != 0) {
                throw new IllegalArgumentException("Unclosed quote in console command");
            }
            tokens.add(new Token(value.toString(), start, index));
        }
        return new ParsedInput(raw, List.copyOf(tokens));
    }

    record ParsedInput(String raw, List<Token> tokens) {
        String value(int index) {
            return tokens.get(index).value();
        }

        int size() {
            return tokens.size();
        }

        String remainderAfterTokens(int count) {
            if (count < 0 || count >= tokens.size()) {
                return "";
            }
            return raw.substring(tokens.get(count).start()).trim();
        }

        List<String> valuesFrom(int index) {
            return tokens.stream().skip(index).map(Token::value).toList();
        }
    }

    record Token(String value, int start, int end) {
    }
}
