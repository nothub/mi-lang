package org.crayne.mi.parsing.parser;

import org.crayne.mi.lang.*;
import org.crayne.mi.parsing.ast.Node;
import org.crayne.mi.parsing.ast.NodeType;
import org.crayne.mi.parsing.lexer.Token;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Stream;

public class ValueParser {

    private int parsingPosition = -1;
    private Token currentToken;
    private final List<Token> expr;
    private final Parser parserParent;

    public record TypedNode(MiDatatype type, Node node) {
        public static TypedNode empty() {
            return new TypedNode(null, new Node(NodeType.VALUE, -1));
        }
        public int lineDebugging() {
            return node == null ? -1 : node.lineDebugging();
        }
    }

    public ValueParser(@NotNull final List<Token> expr, @NotNull final Parser parserParent) {
        this.expr = expr;
        this.parserParent = parserParent;
    }

    private void nextPart() {
        ++parsingPosition;
        currentToken = parsingPosition >= 0 && parsingPosition < expr.size() ? expr.get(parsingPosition) : null;
    }

    public TypedNode parse() {
        nextPart();
        final TypedNode x = parseExpression();
        if (parsingPosition < expr.size()) {
            final Token val = expr.get(parsingPosition);
            parserParent.parserError("Unexpected token '" + expr.get(parsingPosition).token() + "', couldn't parse expression", val);
        }
        return x;
    }

    private boolean ternaryExpectColon(@NotNull final TypedNode y) {
        if (NodeType.of(currentToken) != NodeType.COLON) {
            parserParent.parserError("Expected ':' after ternary 'if'", y.node.value());
            return true;
        }
        return false;
    }

    private boolean ternaryIfElseNotEqual(@NotNull final TypedNode z, @NotNull final TypedNode y) {
        if (!z.type.equals(y.type)) {
            parserParent.parserError("'if' part of ternary operator should (atleast implicitly) have the same type as the 'else' part of the ternary operator", z.node.value(),
                    "'if' part is of type " + y.type + ", while 'else' part is " + z.type + ". Use std.to_nonnull() to explicitely convert nullable types into nonnull types");
            return true;
        }
        return false;
    }

    private boolean ternaryConditionNotBoolean(@NotNull final TypedNode x) {
        if (!MiDatatype.match(x.type, MiDatatype.BOOL)) {
            parserParent.parserError("Ternary operator condition should be of type 'nonnull bool' but is instead '" + x.type + "'", x.node.value());
            return true;
        }
        return false;
    }

    private TypedNode evalTernaryOperator(final TypedNode x, final TypedNode y) {
        if (ternaryExpectColon(y)) return TypedNode.empty();
        nextPart();

        final TypedNode z = parseExpression();

        if (ternaryIfElseNotEqual(z, y)) return TypedNode.empty();
        if (ternaryConditionNotBoolean(x)) return TypedNode.empty();

        return new TypedNode(y.type, new Node(NodeType.TERNARY_OPERATOR, -1,
                new Node(NodeType.CONDITION, -1, x.node),
                new Node(NodeType.TERNARY_OPERATOR_IF, -1, y.node),
                new Node(NodeType.TERNARY_OPERATOR_ELSE, -1, z.node)
        ));
    }

    private static boolean isComparator(@NotNull final String token) {
        return switch (NodeType.of(token)) {
            case EQUALS, NOTEQUALS, GREATER_THAN, GREATER_THAN_EQ, LESS_THAN, LESS_THAN_EQ -> true;
            default -> false;
        };
    }

    private TypedNode evalExpression(final TypedNode x, final TypedNode y, final Token op) {
        if (NodeType.of(op) == NodeType.QUESTION_MARK) return evalTernaryOperator(x, y);

        return new TypedNode(
                isComparator(op.token()) ? MiDatatype.BOOL : MiDatatype.heavier(x.type, y.type),
                new Node(NodeType.of(op.token()), -1, x.node, y.node));
    }

    private static final List<List<NodeType>> operatorPrecedence = Arrays.asList(
            List.of(NodeType.MULTIPLY, NodeType.DIVIDE, NodeType.MODULUS),
            List.of(NodeType.ADD, NodeType.SUBTRACT),
            List.of(NodeType.RSHIFT, NodeType.LSHIFT),
            List.of(NodeType.LESS_THAN, NodeType.GREATER_THAN, NodeType.LESS_THAN_EQ, NodeType.GREATER_THAN_EQ),
            List.of(NodeType.EQUALS, NodeType.NOTEQUALS),
            List.of(NodeType.BIT_AND),
            List.of(NodeType.XOR),
            List.of(NodeType.BIT_OR),
            List.of(NodeType.LOGICAL_AND),
            List.of(NodeType.LOGICAL_OR),
            List.of(NodeType.QUESTION_MARK)
    );

    private TypedNode parseExpression() {
        return parseExpression(operatorPrecedence.size() - 1);
    }

    private TypedNode parseExpression(final int precendece) {
        TypedNode nodeX = precendece > 0 ? parseExpression(precendece - 1) : parseFactor();
        if (nodeX == null) {
            parserParent.parserError("Unexpected parsing error", currentToken);
            return TypedNode.empty();
        }
        for (; ; ) {
            try {
                if (currentToken != null && precendece >= 0 && (operatorPrecedence.get(precendece).contains(NodeType.of(currentToken.token())))) {
                    final Token op = currentToken;
                    nextPart();
                    final TypedNode nodeY = parseExpression(precendece - 1);
                    nodeX = evalExpression(nodeX, nodeY, op);
                } else {
                    return nodeX;
                }
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean noTypeMatches(@NotNull final TypedNode factor, @NotNull final MiDatatype... types) {
        return Stream.of(types).noneMatch(factor.type::equals);
    }

    private void cannotUseOperatorError(@NotNull final TypedNode fact, @NotNull final Token prev) {
        parserParent.parserError("Cannot use '" + prev.token() + "' operator on type '" + fact.type + "'", prev);
    }

    private boolean cannotUseOperator(@NotNull final TypedNode fact, @NotNull final Token prev, @NotNull final MiDatatype... types) {
        if (noTypeMatches(fact, types)) {
            cannotUseOperatorError(fact, prev);
            return true;
        }
        return false;
    }

    private boolean cannotUseNumberOperator(@NotNull final TypedNode fact, @NotNull final Token prev) {
        return cannotUseOperator(fact, prev, MiDatatype.INT, MiDatatype.LONG, MiDatatype.DOUBLE, MiDatatype.FLOAT);
    }

    private boolean cannotUseBitwiseOperator(@NotNull final TypedNode fact, @NotNull final Token prev) {
        return cannotUseOperator(fact, prev, MiDatatype.INT, MiDatatype.LONG, MiDatatype.CHAR);
    }

    private boolean cannotUseBooleanOperator(@NotNull final TypedNode fact, @NotNull final Token prev) {
        return cannotUseOperator(fact, prev, MiDatatype.BOOL);
    }

    private static TypedNode embraceFactor(@NotNull final TypedNode factor, @NotNull final NodeType nodeType) {
        return new TypedNode(factor.type,
                new Node(
                        nodeType,
                        -1,
                        factor.node
                )
        );
    }

    private TypedNode parseNumberFactor(@NotNull final Token prev) {
        if (expectValue(prev)) return TypedNode.empty();
        nextPart();

        final TypedNode fact = parseFactor();
        return cannotUseNumberOperator(fact, prev) ? TypedNode.empty() : fact;
    }

    private TypedNode negateNumberFactor(@NotNull final Token prev) {
        if (expectValue(prev)) return TypedNode.empty();
        nextPart();

        final TypedNode fact = parseFactor();
        final TypedNode negated = embraceFactor(fact, NodeType.NEGATE);
        return cannotUseNumberOperator(fact, prev) ? TypedNode.empty() : negated;
    }

    private TypedNode invertBooleanFactor(@NotNull final Token prev) {
        if (expectValue(prev)) return TypedNode.empty();
        nextPart();

        final TypedNode fact = parseFactor();
        final TypedNode inverted = embraceFactor(fact, NodeType.BOOL_NOT);
        return cannotUseBooleanOperator(fact, prev) ? TypedNode.empty() : inverted;
    }

    private TypedNode bitwiseInvertIntegerFactor(@NotNull final Token prev) {
        if (expectValue(prev)) return TypedNode.empty();
        nextPart();

        final TypedNode fact = parseFactor();
        final TypedNode inverted = embraceFactor(fact, NodeType.BIT_NOT);
        return cannotUseBitwiseOperator(fact, prev) ? TypedNode.empty() : inverted;
    }

    private TypedNode castFactor(@NotNull final Token prev) {
        final String datatype = prev.token();
        if (expectValue(prev)) return TypedNode.empty();
        nextPart();

        return castValue(parseFactor(), Token.of(datatype));
    }

    private boolean expectValue(@NotNull final Token prev) {
        if (parsingPosition + 1 >= expr.size()) {
            parserParent.parserError("Expected value after '" + prev.token() + "'", prev);
            return true;
        }
        return false;
    }

    private Optional<TypedNode> handleFactorPrefixes() {
        final Token prev = currentToken;

        final NodeType tokenType = NodeType.of(prev);
        if (NodeType.of(prev).isDatatype()) return Optional.of(castFactor(prev));

        return Optional.ofNullable(switch (tokenType) {
            case ADD, INCREMENT_LITERAL, DECREMENT_LITERAL -> parseNumberFactor(prev); // +, ++ and -- as PREFIXES do not change a number, but they only make sense when used on any number datatype
            case SUBTRACT -> negateNumberFactor(prev);
            case EXCLAMATION_MARK -> invertBooleanFactor(prev);
            case TILDE -> bitwiseInvertIntegerFactor(prev);
            default -> null;
        });
    }

    private TypedNode evalEnumMember(@NotNull final Token nextPart) {
        final Token enumMember = expr.get(parsingPosition + 2);
        if (NodeType.of(enumMember) == NodeType.IDENTIFIER) {
            final Token enumName = currentToken;

            nextPart();
            nextPart();
            nextPart();
            final MiDatatype miDatatype = new MiDatatype(enumName.token(), false);

            return new TypedNode(miDatatype, new Node(NodeType.GET_ENUM_MEMBER, enumName.actualLine(),
                    new Node(NodeType.IDENTIFIER, enumName, enumName.actualLine()),
                    new Node(NodeType.MEMBER, enumMember, enumMember.actualLine())
            ));
        } else {
            parserParent.parserError("Unexpected token '::'", nextPart);
            return TypedNode.empty();
        }
    }

    private TypedNode evalFunctionCall() {
        final Token identifier = currentToken;
        final List<TypedNode> parsedArgs = parseArgs();

        return new TypedNode(MiDatatype.VOID, new Node(NodeType.FUNCTION_CALL, identifier.actualLine(),
                new Node(NodeType.IDENTIFIER, identifier, identifier.actualLine()),
                new Node(NodeType.PARAMETERS, identifier.actualLine(), parsedArgs == null ? Collections.emptyList() : parsedArgs.stream().map(n ->
                        new Node(NodeType.PARAMETER, n.lineDebugging(),
                                new Node(NodeType.VALUE, n.lineDebugging(), n.node()),
                                new Node(NodeType.TYPE, Token.of(n.type().name()), n.lineDebugging())
                        )
                ).toList())
        ));
    }

    private TypedNode evalVariable(final Token nextPart) {

        final Token identifier = currentToken;
        if (nextPart != null) {
            final Optional<MiEqualOperator> eq = MiEqualOperator.of(nextPart.token());
            if (eq.isPresent()) {
                nextPart();
                nextPart();

                final TypedNode val = NodeType.of(nextPart).incrementDecrement() ? null : parseExpression();

                return new TypedNode(MiDatatype.VOID, null);
            }
        }
        final TypedNode result = new TypedNode(MiDatatype.VOID, new Node(NodeType.IDENTIFIER, identifier, identifier.actualLine()));
        nextPart();
        return result;
    }

    private TypedNode evalStructCreation(@NotNull final Token nextPart) {
        final MiDatatype miDatatype = new MiDatatype(nextPart.token(), false);
        return new TypedNode(miDatatype, new Node(NodeType.STRUCT_CONSTRUCT, nextPart.actualLine(),
                new Node(NodeType.IDENTIFIER, nextPart, nextPart.actualLine())
        ));
    }

    private TypedNode parseFactor() {
        final Optional<TypedNode> prefixed = handleFactorPrefixes();
        if (prefixed.isPresent()) return prefixed.get();

        if (currentToken == null) {
            parserParent.parserError("Unexpected parsing error", expr.get(0));
            return TypedNode.empty();
        }
        final Token nextPart = parsingPosition + 1 < expr.size() ? expr.get(parsingPosition + 1) : null;
        final NodeType currentType = NodeType.of(currentToken);
        final NodeType nextType = nextPart != null ? NodeType.of(nextPart) : null;

        switch (currentType) {
            case IDENTIFIER -> {
                if (nextType != null) switch (nextType) {
                    case DOUBLE_COLON -> {
                        if (parsingPosition + 2 >= expr.size()) {
                            parserParent.parserError("Expected enum ordinal identifier after '::'", currentToken);
                            return TypedNode.empty();
                        }
                        return evalEnumMember(nextPart);
                    }
                    case LPAREN -> {
                        return evalFunctionCall();
                    }
                }
                return evalVariable(nextPart);
            }
            case LITERAL_NEW -> {
                if (nextType != NodeType.IDENTIFIER) {
                    parserParent.parserError("Expected identifier after 'new'", currentToken);
                    return TypedNode.empty();
                }
                nextPart();
                nextPart();
                return evalStructCreation(nextPart);
            }
            case LPAREN -> {
                nextPart();
                final TypedNode result = parseExpression();
                if (!currentToken.token().equals(")"))
                    parserParent.parserError("Expected ')' after expression in parenthesis", expr.get(expr.size() - 1));
                nextPart();
                return result;
            }
            default -> {
                final Token result = currentToken;
                final NodeType nodeType = result == null ? null : NodeType.of(result.token());
                final MiDatatype miDatatype = nodeType == null ? null : NodeType.getAsDataType(new Node(nodeType, result, result.actualLine()));
                if (miDatatype == null) return TypedNode.empty();
                nextPart();
                return new TypedNode(miDatatype, new Node(nodeType, result, result.actualLine()));
            }
        }
    }

    private List<TypedNode> parseArgs() {
        int foundEndingParen = -1;
        final int start = parsingPosition + 1;
        int paren = 0;
        while (parsingPosition < expr.size()) {
            if (currentToken.token().equals("(")) paren++;
            if (currentToken.token().equals(")")) {
                paren--;
                if (paren <= 0) {
                    foundEndingParen = parsingPosition;
                    break;
                }
            }
            nextPart();
        }
        if (foundEndingParen == -1) {
            parserParent.parserError("Expected ')' after arguments of function call", currentToken);
            return null;
        }
        nextPart();
        return parseParametersCallFunction(expr.subList(start + 1, foundEndingParen));
    }

    public static List<TypedNode> parseParametersCallFunction(@NotNull final List<Token> tokens, @NotNull final Parser parserParent) {
        final List<TypedNode> result = new ArrayList<>();

        if (tokens.isEmpty()) return result;

        final List<Token> currentArg = new ArrayList<>();
        int paren = 0;
        boolean addedNode = false;
        for (@NotNull final Token token : tokens) {
            final NodeType type = NodeType.of(token.token());

            if (type == NodeType.LPAREN) paren++;
            if (type == NodeType.RPAREN) paren--;
            if (type == NodeType.COMMA && paren == 0) {
                result.add(new ValueParser(currentArg, parserParent).parse());
                currentArg.clear();
                addedNode = true;
                continue;
            }
            addedNode = false;
            currentArg.add(token);
        }
        if (!addedNode) result.add(new ValueParser(currentArg, parserParent).parse());
        return result;
    }

    private List<TypedNode> parseParametersCallFunction(@NotNull final List<Token> tokens) {
        return parseParametersCallFunction(tokens, parserParent);
    }

    private TypedNode castValue(final TypedNode value, final Token castType) {
        return new TypedNode(MiDatatype.of(castType.token(), false), new Node(NodeType.CAST_VALUE, castType, castType.actualLine(), value.node));
    }

}