package org.crayne.mi.parsing.parser;

import org.apache.commons.lang3.StringUtils;
import org.crayne.mi.lang.Module;
import org.crayne.mi.lang.*;
import org.crayne.mi.parsing.ast.Node;
import org.crayne.mi.parsing.ast.NodeType;
import org.crayne.mi.parsing.lexer.Token;
import org.crayne.mi.parsing.parser.scope.*;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ParserEvaluator {

    final Parser parser;

    public ParserEvaluator(@NotNull final Parser parser) {
        this.parser = parser;
    }

    private static boolean restrictedName(@NotNull final String name) {
        return name.contains(".");
    }

    private boolean handleRestrictedName(@NotNull final Token name) {
        final String tok = name.token();
        if (!restrictedName(tok)) return false;

        parser.parserError("Cannot use restricted name '" + tok + "'", name,
                "'.' characters are only allowed when searching for submodules or functions inside of modules");
        return true;
    }


    protected void addGlobalVarFromResult(@NotNull final Node result) {
        final Module module = parser.lastModule();
        final List<Modifier> modifiers = result.child(0).children().stream().map(n -> Modifier.of(n.type())).toList();
        final Token ident = result.child(1).value();
        final Optional<Scope> current = parser.scope();

        final Datatype datatype = Datatype.of(parser, Collections.emptyList(), result.child(2).value(), modifiers.contains(Modifier.NULLABLE));
        if (datatype == null) return;
        final boolean init = result.children().size() == 4;

        final Variable var = new Variable(
                ident.token(),
                datatype,
                modifiers,
                module,
                true,
                init ? result.child(3) : null
        );

        if (current.isPresent() && current.get() instanceof final ClassScope classScope) {
            classScope.addVar(parser, var, ident);
            return;
        }

        if (!parser.stdlib && (current.isEmpty() || current.get().type() != ScopeType.MODULE)) {
            parser.parserError("Cannot define global variables at root level", ident,
                    "Move your variable into a module or create a new module for it");
            return;
        }
        if (handleRestrictedName(ident)) return;
        module.addGlobalVariable(parser, var);
    }

    public void addLocalVarFromResult(@NotNull final Node result) {
        final List<Modifier> modifiers = result.child(0).children().stream().map(n -> Modifier.of(n.type())).toList();

        final Token ident = result.child(1).value();
        final FunctionScope functionScope = expectFunctionScope(ident);

        final Datatype datatype = Datatype.of(parser, result.child(2).value(), modifiers.contains(Modifier.NULLABLE));
        if (functionScope == null || datatype == null) return;

        final LocalVariable var = new LocalVariable(new Variable(
                ident.token(),
                datatype,
                modifiers,
                null,
                result.children().size() == 4,
                null
        ), functionScope);

        if (handleRestrictedName(ident)) return;

        functionScope.addLocalVariable(parser, var, ident);
    }

    protected void addClassFromResult(@NotNull final Node result) {
        if (!parser.skimming) return;
        final Module module = parser.lastModule();
        final Token nameToken = result.child(0).value();
        final ClassScope currentClass = parser.currentClass;
        if (currentClass == null) {
            parser.parserError("Unexpected parsing error, class without class scope", nameToken);
            return;
        }
        module.addClass(parser, currentClass.createClass(), nameToken);
    }

    protected void addFunctionFromResult(@NotNull final Node result) {
        if (!parser.skimming) return;
        final Module module = parser.lastModule();
        try {
            final List<Modifier> modifiers = result.child(2).children().stream().map(n -> Modifier.of(n.type())).toList();
            final List<Node> paramNodes = result.child(3).children().stream().toList();

            final List<FunctionParameter> params = paramNodes.stream().map(n -> {
                final List<Modifier> modifs = n.child(2).children().stream().map(n2 -> Modifier.of(n2.type())).toList();
                final Datatype datatype = Datatype.of(parser, n.child(0).value(), modifs.contains(Modifier.NULLABLE));
                if (datatype == null) throw new NullPointerException();
                return new FunctionParameter(
                        datatype,
                        n.child(1).value().token(),
                        modifs
                );
            }).toList();

            final Token nameToken = result.child(0).value();
            if (handleRestrictedName(nameToken)) return;

            final Datatype datatype = Datatype.of(parser, result.child(1).value(), modifiers.contains(Modifier.NULLABLE));
            if (datatype == null) throw new NullPointerException();

            final Optional<Scope> current = parser.scope();
            if (current.isPresent() && current.get() instanceof final ClassScope classScope) {
                if (result.children().size() == 5) {
                    classScope.addMethod(parser, nameToken, new FunctionDefinition(
                            nameToken.token(),
                            datatype,
                            params,
                            modifiers,
                            module,
                            result.child(4)
                    ));
                    return;
                }
                classScope.addMethod(parser, nameToken, new FunctionDefinition(
                        nameToken.token(),
                        datatype,
                        params,
                        modifiers,
                        module,
                        false
                ));
            }

            if (result.children().size() == 5) {
                module.addFunction(parser, nameToken, new FunctionDefinition(
                        nameToken.token(),
                        datatype,
                        params,
                        modifiers,
                        module,
                        result.child(4)
                ));
                return;
            }
            module.addFunction(parser, nameToken, new FunctionDefinition(
                    nameToken.token(),
                    datatype,
                    params,
                    modifiers,
                    module,
                    false
            ));
        } catch (final NullPointerException e) {
            e.printStackTrace();
            parser.output.errorMsg("Could not parse function definition");
        }
    }

    protected void addNativeFunctionFromResult(@NotNull final Node result, final Method nativeMethod, final Class<?> nativeCallClass) {
        if (!parser.skimming) return;
        final Module module = parser.lastModule();
        try {
            final List<Modifier> modifiers = result.child(2).children().stream().map(n -> Modifier.of(n.type())).toList();

            final List<Node> paramNodes = result.child(3).children().stream().toList();

            final List<FunctionParameter> params = paramNodes.stream().map(n -> {
                final List<Modifier> modifs = n.child(2).children().stream().map(n2 -> Modifier.of(n2.type())).toList();
                final Datatype datatype = Datatype.of(parser, n.child(0).value(), modifs.contains(Modifier.NULLABLE));
                if (datatype == null) throw new NullPointerException();
                return new FunctionParameter(
                        datatype,
                        n.child(1).value().token(),
                        modifs
                );
            }).toList();

            final Token nameToken = result.child(0).value();
            if (handleRestrictedName(nameToken)) return;

            final Datatype datatype = Datatype.of(parser, result.child(1).value(), modifiers.contains(Modifier.NULLABLE));
            if (datatype == null) throw new NullPointerException();

            if (result.children().size() == 5) {
                if (nativeMethod != null) {
                    module.addFunction(parser, nameToken, new FunctionDefinition(
                            nameToken.token(),
                            datatype,
                            params,
                            modifiers,
                            module,
                            nativeMethod,
                            nativeCallClass
                    ));
                    return;
                }
                module.addFunction(parser, nameToken, new FunctionDefinition(
                        nameToken.token(),
                        datatype,
                        params,
                        modifiers,
                        module,
                        result.child(4)
                ));
            }
        } catch (final NullPointerException e) {
            e.printStackTrace();
            parser.output.errorMsg("Could not parse function definition");
        }
    }

    public Node evalReturnStatement(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;

        final Token ret = parser.getAndExpect(tokens, 0, NodeType.LITERAL_RET, NodeType.DOUBLE_COLON);
        if (ret == null) return null;

        final FunctionScope functionScope = expectFunctionScope(tokens.get(0));
        if (functionScope == null) return null;

        final Datatype expectedType = parser.currentFuncReturnType;
        if (expectedType == null) { // should never happen but just in case i guess?
            parser.parserError("Unexpected parsing error, the datatype of the current function is unknown");
            return null;
        }
        if (tokens.size() == 2) { // ret ; are two tokens
            if (expectedType != Datatype.VOID) {
                parser.parserError("Expected datatype of return value to be " + expectedType + ", but got void instead", tokens.get(1));
                return null;
            }
            functionScope.reachedEnd();
            return new Node(NodeType.RETURN_VALUE);
        }

        final ValueParser.TypedNode retVal = parseExpression(tokens.subList(1, tokens.size() - 1));
        if (retVal == null || retVal.type() == null || retVal.node() == null) return null;

        if (!retVal.type().equals(expectedType)) {
            parser.parserError("Expected datatype of return value to be " + expectedType + ", but got " + retVal.type() + " instead", tokens.get(1));
            return null;
        }
        functionScope.reachedEnd();

        return new Node(NodeType.RETURN_VALUE, ret.actualLine(), new Node(NodeType.VALUE, ret.actualLine(), retVal.node()));
    }

    public Node evalFirstIdentifier(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        final Token secondToken = parser.getAny(tokens, 1);
        if (Parser.anyNull(secondToken)) return null;

        final NodeType second = NodeType.of(secondToken);
        if (second == NodeType.LPAREN) return evalFunctionCall(tokens, modifiers);
        else if (second == NodeType.COMMA || second == NodeType.SEMI) return evalEnumMembers(tokens, modifiers);
        else if (second == NodeType.IDENTIFIER) return evalVariableDefinition(tokens, modifiers);
        else if (second.getAsString() != null && EqualOperation.of(second.getAsString()) != null) return evalVariableChange(tokens, modifiers);
        return null;
    }

    private Node evalEnumMembers(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;
        final EnumScope enumScope = expectEnumScope(tokens.get(0));
        if (enumScope == null) return null;

        final List<String> children = extractIdentifiers(tokens.subList(0, tokens.size() - 1));
        if (children == null) return null;
        if (enumScope.hasMembers()) {
            parser.parserError("Redefinition of enum members", tokens.get(0), "Delete redefinition");
            return null;
        }
        enumScope.addMembers(children);

        return new Node(NodeType.ENUM_VALUES,
                children.stream().map(s -> new Node(NodeType.IDENTIFIER, Token.of(s))).collect(Collectors.toList())
        );
    }

    private List<String> extractIdentifiers(@NotNull final List<Token> tokens) {
        final List<String> result = new ArrayList<>();
        String current = null;

        for (@NotNull final Token token : tokens) {
            if (NodeType.of(token) == NodeType.COMMA) {
                if (current == null) {
                    parser.parserError("Unexpected token ','", token);
                    return null;
                }
                if (NodeType.of(current) != NodeType.IDENTIFIER) {
                    parser.parserError("Expected identifier", token);
                    return null;
                }
                if (result.contains(current)) {
                    parser.parserError("Redefinition of identifier '" + current + "'", token);
                    return null;
                }
                result.add(current);
                current = null;
                continue;
            }
            if (current != null) {
                parser.parserError("Expected ','");
                return null;
            }
            current = token.token();
        }
        if (current != null) result.add(current);
        return result;
    }

    public Node evalVariableChange(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;
        final Token identifier = parser.getAndExpect(tokens, 0, NodeType.IDENTIFIER);
        final Token equal = parser.getAndExpect(tokens, 1, NodeType.SET, NodeType.SET_ADD, NodeType.SET_AND, NodeType.SET_DIV, NodeType.SET_LSHIFT,
                NodeType.SET_MOD, NodeType.SET_MULT, NodeType.SET_OR, NodeType.SET_RSHIFT, NodeType.SET_SUB, NodeType.SET_XOR,
                NodeType.INCREMENT_LITERAL, NodeType.DECREMENT_LITERAL);

        if (Parser.anyNull(identifier, equal)) return null;

        final ValueParser.TypedNode value = NodeType.of(equal) == NodeType.INCREMENT_LITERAL || NodeType.of(equal) == NodeType.DECREMENT_LITERAL
                ? new ValueParser.TypedNode(Datatype.INT,
                        new Node(NodeType.INTEGER_NUM_LITERAL, Token.of("1"))
                )
                : parseExpression(tokens.subList(2, tokens.size() - 1));


        final Optional<Variable> foundVariable = findVariable(identifier, true);
        if (foundVariable.isEmpty()) return null;

        return evalVariableChange(identifier, value, equal, foundVariable.get());
    }

    public Node evalVariableChange(@NotNull final Token identifier, @NotNull final ValueParser.TypedNode value, @NotNull final Token equal, @NotNull final Variable var) {
        final FunctionScope functionScope = expectFunctionScope(identifier);
        if (functionScope == null) return null;

        final EqualOperation eq = EqualOperation.of(equal.token());
        if (eq == null) {
            parser.parserError("Unexpected parsing error, invalid equals operation '" + equal.token() + "'", equal);
            return null;
        }
        if (eq != EqualOperation.EQUAL && !var.type().operatorDefined(NodeType.of(equal.token().substring(0, 1)), var.type())) {
            parser.parserError("Undefined operator '" + equal.token() + "' for datatype '" + var.type() + "'", equal);
            return null;
        }

        final boolean success = functionScope.localVariableValue(parser, identifier, value, eq);
        if (!success && parser.encounteredError) return null;

        final NodeType eqType = NodeType.of(equal);
        final Token finalEq = switch (eqType) {
            case INCREMENT_LITERAL -> Token.of("+=");
            case DECREMENT_LITERAL -> Token.of("-=");
            default -> equal;
        };

        return new Node(NodeType.VAR_SET_VALUE, finalEq.actualLine(),
                new Node(NodeType.IDENTIFIER, var.asIdentifierToken(identifier)),
                new Node(NodeType.OPERATOR, finalEq),
                new Node(NodeType.VALUE, -1, value.node())
        );
    }

    protected Optional<Variable> findVariable(@NotNull final Token identifierTok, final boolean panic) {
        final Optional<Scope> scope = parser.scope();
        if (scope.isEmpty()) return Optional.empty();
        if (scope.get() instanceof final FunctionScope functionScope) {
            return Optional.ofNullable(functionScope.localVariable(parser, identifierTok).orElseGet(() -> {
                if (parser.encounteredError)
                    return null; // localVariable() returns null if the needed variable is global but does not actually print an error into the logs

                return findGlobalVariable(identifierTok, functionScope.using(), panic);
            }));
        }
        return Optional.ofNullable(findGlobalVariable(identifierTok, Collections.emptyList(), panic));
    }

    protected Variable findGlobalVariable(@NotNull final Token identifierTok, final List<String> usingMods, final boolean panic) {
        final String identifier = identifierTok.token();
        final Optional<Module> oglobalMod = parser.findModuleFromIdentifier(identifier, identifierTok, panic);
        if (oglobalMod.isEmpty()) {
            if (panic)
                parser.parserError("Unexpected parsing error, module of global variable is null without any previous parsing error", identifierTok);
            return null;
        }
        final Module globalMod = oglobalMod.get();
        Optional<Variable> globalVar = globalMod.findVariableByName(ParserEvaluator.identOf(identifier));

        if (globalVar.isEmpty())
            for (final String using : usingMods) {
                final Variable findUsing = findGlobalVariable(new Token(using + "." + identifier, identifierTok.actualLine(), identifierTok.line(), identifierTok.column()),
                        Collections.emptyList(), false);

                if (findUsing != null) globalVar = Optional.of(findUsing);
            }

        if (globalVar.isEmpty()) globalVar = parser.parentModule().findVariableByName(identifier);
        if (globalVar.isEmpty() && parser.currentParsingModule != null) {
            globalVar = parser.currentParsingModule().findVariableByName(identifier);
        }

        if (globalVar.isEmpty()) {
            if (panic)
                parser.parserError("Unexpected parsing error, global variable is null without any previous parsing error", identifierTok);
            return null;
        }

        parser.checkAccessValidity(globalMod, IdentifierType.VARIABLE, identifierTok, globalVar.get().modifiers());
        return globalVar.get();
    }

    public static String moduleOf(@NotNull final String identifier) {
        return identifier.contains(".") ? StringUtils.substringBeforeLast(identifier, ".") : "";
    }

    public static String identOf(@NotNull final String identifier) {
        return identifier.contains(".") ? StringUtils.substringAfterLast(identifier, ".") : identifier;
    }

    public Node evalFunctionCall(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;

        final Token identifierTok = parser.getAndExpect(tokens, 0, NodeType.IDENTIFIER);
        if (Parser.anyNull(identifierTok)) return null;

        final List<ValueParser.TypedNode> params = parseParametersCallFunction(tokens.subList(2, tokens.size() - 2));
        final FunctionDefinition def = checkValidFunctionCall(identifierTok, params, true, true);
        if (def == null) return null;

        return new Node(NodeType.FUNCTION_CALL, identifierTok.actualLine(),
                new Node(NodeType.IDENTIFIER, def.asIdentifierToken(identifierTok)),
                new Node(NodeType.PARAMETERS, params.stream().map(n ->
                        new Node(NodeType.PARAMETER, -1,
                                new Node(NodeType.VALUE, -1, n.node()),
                                new Node(NodeType.TYPE, Token.of(n.type().getName()))
                        )
                ).toList())
        );
    }

    protected FunctionDefinition checkValidFunctionCall(@NotNull final Token identifierTok, @NotNull final List<ValueParser.TypedNode> params, final boolean checkUsing, final boolean panic) {
        final String identifier = identifierTok.token();
        final String moduleAsString = moduleOf(identifier);
        final Optional<Module> ofunctionModule = parser.findModuleFromIdentifier(identifier, identifierTok, panic);
        if (ofunctionModule.isEmpty()) return null;

        final FunctionScope functionScope = expectFunctionScope(identifierTok);
        if (functionScope == null) return null;

        final Module functionModule = ofunctionModule.get();
        final String function = identOf(identifier);
        Optional<FunctionConcept> funcConcept = functionModule.findFunctionConceptByName(parser, identifierTok);

        if (funcConcept.isEmpty()) {
            if (checkUsing) {
                for (final String using : functionScope.using()) {
                    final Optional<Module> mod = parser.parentModule().subModules().stream().filter(m -> m.name().equals(using)).findFirst();
                    if (mod.isEmpty()) continue;
                    final Optional<FunctionConcept> findUsing = mod.get().findFunctionConceptByName(parser, identifierTok);
                    if (findUsing.isPresent()) {
                        funcConcept = findUsing;
                        break;
                    }
                }
            }
        }
        if (funcConcept.isEmpty()) funcConcept = parser.currentParsingModule != null ? parser.currentParsingModule.findFunctionConceptByName(parser, identifierTok) : Optional.empty();
        if (funcConcept.isEmpty()) {
            if (panic) parser.parserError("Cannot find any function called '" + function + "' in module '" +
                    (moduleAsString.isEmpty() ? parser.lastModule().name() : moduleAsString) + "'", identifierTok.line(), identifierTok.column() + moduleAsString.length());
            return null;
        }
        final Optional<FunctionDefinition> def = funcConcept.get().definitionByCallParameters(params);
        if (parser.encounteredError) return null;

        if (def.isEmpty()) {
            if (params.isEmpty()) {
                if (panic) parser.parserError("Cannot find any implementation for function '" + function + "' with no arguments", identifierTok, true);
                return null;
            }
            if (panic) parser.parserError("Cannot find any implementation for function '" + function + "' with argument types " + callArgsToString(params), identifierTok, true);
            return null;
        }
        if (panic) parser.checkAccessValidity(functionModule, IdentifierType.FUNCTION, identifierTok, def.get().modifiers());
        if (parser.encounteredError) return null;
        return def.get();
    }

    private String callArgsToString(@NotNull final List<ValueParser.TypedNode> params) {
        return String.join(", ", params.stream().map(n -> n.type().toString()).toList());
    }

    public List<ValueParser.TypedNode> parseParametersCallFunction(@NotNull final List<Token> tokens) {
        return ValueParser.parseParametersCallFunction(tokens, parser);
    }

    public Node evalVariableDefinition(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        final Token identifier = parser.getAndExpect(tokens, 1, NodeType.IDENTIFIER);
        final Token equalsOrSemi = parser.getAndExpect(tokens, 2, NodeType.SET, NodeType.SEMI);
        if (Parser.anyNull(identifier, equalsOrSemi)) return null;

        final Token datatype = tokens.get(0);
        final boolean indefinite = NodeType.of(datatype) == NodeType.QUESTION_MARK;

        if (NodeType.of(equalsOrSemi) == NodeType.SEMI) {
            if (indefinite) {
                parser.parserError("Unexpected token '?', expected a definite datatype",
                        "The '?' cannot be used as a datatype when there is no value directly specified, so change the datatype to a definite.");
                return null;
            }

            return new Node(NodeType.VAR_DEFINITION, identifier.actualLine(),
                    new Node(NodeType.MODIFIERS, modifiers),
                    new Node(NodeType.IDENTIFIER, identifier),
                    new Node(NodeType.TYPE, datatype)
            );
        }
        final ValueParser.TypedNode value = parseExpression(tokens.subList(3, tokens.size() - 1));
        if (value.type() == null || value.node() == null) return null;
        if (NodeType.of(datatype) == NodeType.QUESTION_MARK && value.type() == Datatype.NULL) {
            parser.parserError("Unexpected token '?', expected a definite datatype", datatype,
                    "The '?' cannot be used as a datatype when there is a direct literal null specified, so change the datatype to a definite.");
            return null;
        }

        final Node finalType = indefinite
                ? new Node(NodeType.TYPE, Token.of(value.type().getName()))
                : new Node(NodeType.TYPE, datatype);

        final Datatype type = Datatype.of(parser, finalType.value(), modifiers.stream().map(n -> Modifier.of(n.type())).toList().contains(Modifier.NULLABLE));
        if (!indefinite && !Datatype.equal(value.type(), Objects.requireNonNull(type))) {
            parser.parserError("Datatypes are not equal on both sides, trying to assign " + value.type() + " to a " + type + " variable.", datatype,
                    "Change the datatype to the correct one, try casting values inside the expression to the needed datatype or set the variable type to '?'.");
            return null;
        }

        return new Node(NodeType.VAR_DEF_AND_SET_VALUE, identifier.actualLine(),
                new Node(NodeType.MODIFIERS, modifiers),
                new Node(NodeType.IDENTIFIER, identifier),
                finalType,
                new Node(NodeType.VALUE, identifier.actualLine(), value.node())
        );
    }

    public Node evalBreak(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;
        final Token breakToken = parser.getAndExpect(tokens, 0, NodeType.LITERAL_BREAK);
        final Token semi = parser.getAndExpect(tokens, 1, NodeType.SEMI);
        if (Parser.anyNull(breakToken, semi)) return null;

        final LoopScope expectFunctionScope = expectLoopScope(breakToken);
        if (expectFunctionScope == null) return null;

        expectFunctionScope.reachedLoopStepBreak();

        return new Node(NodeType.BREAK_STATEMENT, breakToken);
    }

    public Node evalContinue(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;
        final Token continueToken = parser.getAndExpect(tokens, 0, NodeType.LITERAL_CONTINUE);
        final Token semi = parser.getAndExpect(tokens, 1, NodeType.SEMI);
        if (Parser.anyNull(continueToken, semi)) return null;

        final LoopScope expectFunctionScope = expectLoopScope(continueToken);
        if (expectFunctionScope == null) return null;

        expectFunctionScope.reachedLoopStepBreak();

        return new Node(NodeType.CONTINUE_STATEMENT, continueToken);
    }

    public ValueParser.TypedNode parseExpression(@NotNull final List<Token> tokens) {
        return new ValueParser(tokens, parser).parse();
    }

    public List<Node> parseParametersDefineFunction(@NotNull final List<Token> tokens) {
        final List<Node> result = new ArrayList<>();
        if (tokens.isEmpty()) return result;
        Node currentNode = new Node(NodeType.VAR_DEFINITION);
        final List<NodeType> currentNodeModifiers = new ArrayList<>();
        boolean addedNode = false;
        boolean parsedDatatype = false;
        boolean parsedIdentifier = false;

        for (@NotNull final Token token : tokens) {
            final Node asNode = new Node(NodeType.of(token), token);
            final NodeType type = asNode.type();
            final Datatype asDatatype = parsedDatatype ? null : Datatype.of(parser, Collections.emptyList(), token, currentNodeModifiers.stream().map(Modifier::of).toList().contains(Modifier.NULLABLE));

            if (type == NodeType.COMMA) {
                currentNode.addChildren(new Node(NodeType.MODIFIERS, currentNodeModifiers.stream().map(Node::of).toList()));
                result.add(currentNode);
                currentNode = new Node(NodeType.VAR_DEFINITION);
                currentNodeModifiers.clear();
                parsedDatatype = false;
                parsedIdentifier = false;
                addedNode = true;
                continue;
            }
            addedNode = false;
            if (type.isModifier()) {
                if (parsedIdentifier || parsedDatatype) {
                    parser.parserError("Unexpected token '" + token.token() + "' while parsing function parameters, expected modifiers before datatype before identifier");
                    return new ArrayList<>();
                }
                if (type.isVisibilityModifier()) {
                    parser.parserError("Unexpected token '" + token.token() + "', cannot use visibility modifiers (pub, priv, own) for function parameters");
                }
                if (parser.findConflictingModifiers(currentNodeModifiers, type, token)) return new ArrayList<>();
                currentNodeModifiers.add(type);
                continue;
            }
            if (asDatatype != null && asDatatype.valid()) {
                parsedDatatype = true;
                currentNode.addChildren(asNode);
                continue;
            }
            if (asDatatype == null || !asDatatype.valid()) {
                if (!parsedDatatype) {
                    parser.parserError("Unexpected token '" + token.token() + "' while parsing function parameters, expected datatype before identifier");
                    return new ArrayList<>();
                }
                parsedIdentifier = true;
                currentNode.addChildren(asNode);
                continue;
            }
            parser.parserError("Could not parse function argument, unexpected token '" + token.token() + "'");
            return new ArrayList<>();
        }
        if (!addedNode) {
            currentNode.addChildren(new Node(NodeType.MODIFIERS, currentNodeModifiers.stream().map(Node::of).toList()));
            result.add(currentNode);
            result.forEach(n -> {
                if (n.child(1).value() == null) {
                    parser.parserError("Expected identifier after datatype", n.child(0).value(), true);
                }
            });
            if (parser.encounteredError) return new ArrayList<>();

            final Set<String> duplicates = findFirstDuplicate(result.stream().map(n -> n.child(1).value().token()).toList());
            if (!duplicates.isEmpty()) {
                final String duplicate = duplicates.stream().toList().get(0);
                parser.parserError("Redefinition of function argument '" + duplicate + "'", tokens.get(0));
                return new ArrayList<>();
            }
        }
        return result;
    }

    public static <T> Set<T> findFirstDuplicate(@NotNull final List<T> list) {
        final Set<T> items = new HashSet<>();
        return list.stream()
                .filter(n -> !items.add(n))
                .collect(Collectors.toSet());

    }

    public Node evalFunctionDefinition(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {

        final Token fnToken = parser.getAndExpect(tokens, 0, NodeType.LITERAL_FN);
        final Token identifier = parser.getAndExpect(tokens, 1, NodeType.IDENTIFIER);
        final Token retDef = parser.getAndExpect(tokens, 2, NodeType.TILDE, NodeType.DOUBLE_COLON, NodeType.LBRACE);
        final Token last = parser.getAndExpect(tokens, tokens.size() - 1, NodeType.SEMI, NodeType.LBRACE);
        if (Parser.anyNull(fnToken, identifier, retDef, last)) return null;

        final Optional<Node> firstMutabilityModif = modifiers.stream().filter(m -> m.type().isMutabilityModifier()).findFirst();
        if (firstMutabilityModif.isPresent()) {
            parser.parserError("Cannot declare functions as own, const or mut, they are automatically constant because they cannot be redefined",
                    firstMutabilityModif.get().value());
            return null;
        }
        final NodeType lastType = NodeType.of(last);
        final Optional<Node> firstNat = modifiers.stream().filter(m -> m.type() == NodeType.LITERAL_NAT).findFirst();
        final boolean nativeFunc = firstNat.isPresent();
        if (nativeFunc) {
            if (lastType == NodeType.LBRACE) {
                parser.parserError("Expected ';' after native function definition", last);
                return null;
            }
        } else if (lastType == NodeType.SEMI) {
            parser.parserError("Expected '{' after intern function definition", last);
            return null;
        }

        final NodeType ret = NodeType.of(retDef);
        if (parser.skimming) {
            final Optional<Scope> current = parser.scope();
            if (current.isEmpty()) {
                parser.parserError("Unexpected parsing error", "Could not create function at root level");
                return null;
            }
            final ScopeType currentType = current.get().type();
            if (!parser.stdlib ? currentType != ScopeType.MODULE && currentType != ScopeType.CLASS : currentType != ScopeType.PARENT && currentType != ScopeType.MODULE && currentType != ScopeType.CLASS) {
                if (parser.stdlib) {
                    parser.parserError("Expected function definition to be inside of a module or at root level",
                            "Cannot define functions inside of other functions");
                    return null;
                }
                parser.parserError("Expected function definition to be inside of a module",
                        "Cannot define functions at root level, create a module for your function or move it to an existing module",
                        "Cannot define functions inside of other functions either");
                return null;
            }
        }
        final List<Modifier> modifs = modifiers.stream().map(n -> Modifier.of(n.type())).toList();
        if (ret == NodeType.LBRACE) {
            if (nativeFunc) {
                parser.parserError("Expected ';' after native function definition", last);
                return null;
            }
            parser.functionRootScope(new FunctionDefinition(identifier.token(), Datatype.VOID, Collections.emptyList(), modifs, parser.skimming ? parser.lastModule() : parser.currentParsingModule, true));
            parser.currentFuncReturnType = Datatype.VOID;
            return new Node(NodeType.FUNCTION_DEFINITION, identifier.actualLine(),
                    new Node(NodeType.IDENTIFIER, identifier),
                    new Node(NodeType.TYPE, Token.of("void")),
                    new Node(NodeType.MODIFIERS, modifiers),
                    new Node(NodeType.PARAMETERS, Collections.emptyList())
            );
        }
        final int extraIndex = ret == NodeType.TILDE ? 0 : 1;
        final Datatype returnType;
        if (extraIndex == 0) returnType = Datatype.VOID;
        else {
            final Token returnToken = parser.getAndExpect(tokens, 3,
                    Arrays.stream(NodeType.values())
                            .filter(t -> t.isDatatype() || t == NodeType.LITERAL_VOID)
                            .toList()
                            .toArray(new NodeType[0]));
            if (returnToken == null) return null;

            returnType = Datatype.of(parser, returnToken, modifs.contains(Modifier.NULLABLE));
        }
        if (returnType == null) return null;

        if (nativeFunc) return evalNativeFunction(tokens, modifiers, extraIndex, last, identifier, returnType);

        final Token parenOpen = parser.getAndExpect(tokens, 3 + extraIndex, NodeType.LPAREN);
        final Token parenClose = parser.getAndExpect(tokens, tokens.size() - 2, true, NodeType.RPAREN);
        if (parenOpen == null || parenClose == null) return null;

        final List<Node> params = parseParametersDefineFunction(tokens.subList(4 + extraIndex, tokens.size() - 2));

        final List<FunctionParameter> parameters = params.stream().map(n -> {
            final List<Modifier> modifs2 = n.child(2).children().stream().map(n2 -> Modifier.of(n2.type())).toList();
            final Datatype datatype = Datatype.of(parser, n.child(0).value(), modifs2.contains(Modifier.NULLABLE));
            if (datatype == null) throw new NullPointerException();
            return new FunctionParameter(
                    datatype,
                    n.child(1).value().token(),
                    modifs2
            );
        }).toList();

        parser.functionRootScope(new FunctionDefinition(identifier.token(), returnType, parameters, modifs, parser.skimming ? parser.lastModule() : parser.currentParsingModule, true));

        final Optional<Scope> current = parser.scope();
        if (current.isPresent() && current.get() instanceof final FunctionScope functionScope) {
            for (final Node param : params) {
                final List<Modifier> paramModifs = param.child(2).children().stream().map(n -> Modifier.of(n.type())).toList();
                final Datatype pType = Datatype.of(parser, param.child(0).value(), paramModifs.contains(Modifier.NULLABLE));
                if (pType == null) return null;
                final String ident = param.child(1).value().token();
                functionScope.addLocalVariable(parser, new LocalVariable(
                        new Variable(
                                ident, pType, paramModifs, null, true, null
                        ), functionScope
                ), param.child(0).value());
            }
        }
        parser.currentFuncReturnType = returnType;

        return new Node(NodeType.FUNCTION_DEFINITION, identifier.actualLine(),
                new Node(NodeType.IDENTIFIER, identifier),
                new Node(NodeType.TYPE, Token.of(returnType.getName())),
                new Node(NodeType.MODIFIERS, modifiers),
                new Node(NodeType.PARAMETERS, params)
        );
    }

    public Node evalNativeFunction(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers, final int extraIndex,
                                   @NotNull final Token last, @NotNull final Token identifier, @NotNull final Datatype returnType) {
        final Token beforeLastArrow = parser.getAny(tokens, tokens.size() - 3);
        if (beforeLastArrow == null || NodeType.of(beforeLastArrow) != NodeType.ARROW) {
            parser.parserError("Expected '-> <constant-string-literal>' after ')' in native function definition", last,
                    "The scheme for native functions is: <modifiers> <identifier> <return-definition> ( <args> ) -> <constant-string-literal>");
            return null;
        }
        final Token stringLiteral = parser.getAndExpect(tokens, tokens.size() - 2, NodeType.STRING_LITERAL);
        if (stringLiteral == null) return null;

        final Token parenOpen = parser.getAndExpect(tokens, 3 + extraIndex, NodeType.LPAREN);
        final Token parenClose = parser.getAndExpect(tokens, tokens.size() - 4, true, NodeType.RPAREN);
        if (parenOpen == null || parenClose == null) return null;

        final List<Node> params = parseParametersDefineFunction(tokens.subList(4 + extraIndex, tokens.size() - 4));

        final Node result = new Node(NodeType.NATIVE_FUNCTION_DEFINITION, identifier.actualLine(),
                new Node(NodeType.IDENTIFIER, identifier),
                new Node(NodeType.TYPE, Token.of(returnType.getName())),
                new Node(NodeType.MODIFIERS, modifiers),
                new Node(NodeType.PARAMETERS, params),
                new Node(NodeType.NATIVE_JAVA_FUNCTION_STR, stringLiteral)
        );

        final List<FunctionParameter> parameters = params.stream().map(n -> {
            final List<Modifier> modifs = n.child(2).children().stream().map(n2 -> Modifier.of(n2.type())).toList();
            final Datatype datatype = Datatype.of(parser, n.child(0).value(), modifs.contains(Modifier.NULLABLE));
            if (datatype == null) throw new NullPointerException();
            return new FunctionParameter(
                    datatype,
                    n.child(1).value().token(),
                    modifs
            );
        }).toList();

        final String c = stringLiteral.token().substring(1, stringLiteral.token().length() - 1);
        final Method nativeMethod = checkNativeFunctionValidity(last, c, identifier.token(), parameters, returnType);
        if (nativeMethod == null) return null;
        try {
            addNativeFunctionFromResult(result, nativeMethod, Class.forName(c));
        } catch (final ClassNotFoundException e) {
            parser.parserError("Cannot find native class '" + c + "' for native function", identifier);
            return null;
        }
        return result;
    }

    public static Class<?> primitiveToJavaType(@NotNull final PrimitiveDatatype primitiveDatatype) {
        return switch (primitiveDatatype) {
            case INT -> Integer.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
            case FLOAT -> Float.class;
            case BOOL -> Boolean.class;
            case STRING -> String.class;
            case CHAR -> Character.class;
            case VOID -> void.class;
            case NULL -> Object.class;
        };
    }

    private Method checkNativeFunctionValidity(@NotNull final Token at,
                                                @NotNull final String className, @NotNull final String functionName, @NotNull final List<FunctionParameter> params, @NotNull final Datatype returnType) {
        try {
            final Class<?> jcallClass = Class.forName(className);
            final ArrayList<Class<?>> paramTypes = new ArrayList<>();

            for (final FunctionParameter arg : params) {
                final Datatype type = arg.type();
                if (type.notPrimitive()) {
                    parser.parserError("Only primitive datatypes (int, long, double, float, bool, string, char) may be used as native function arguments", at);
                    return null;
                }
                paramTypes.add(primitiveToJavaType(type.getPrimitive()));
            }
            if (returnType.notPrimitive()) {
                parser.parserError("Only primitive datatypes (int, long, double, float, bool, string, char) may be used as a native function return type", at);
                return null;
            }
            final Method invokeMethod = jcallClass.getMethod(functionName, paramTypes.toArray(new Class<?>[0]));
            final Annotation[][] paramAnnotations = invokeMethod.getParameterAnnotations();

            for (int i = 0; i < params.size(); i++) {
                final Datatype mutype = params.get(i).type();
                final Annotation[] annotations = paramAnnotations[i];
                if (mutype.nullable() && Stream.of(annotations).map(Annotation::annotationType).toList().contains(Nonnull.class)) {
                    parser.parserError("Parameter #" + i + " of native function is nullable, but the same parameter of the java method is marked as " + Nonnull.class.getName());
                    return null;
                }
            }

            final Class<?> methodType = invokeMethod.getReturnType();
            if (methodType != primitiveToJavaType(returnType.getPrimitive())) {
                parser.parserError("Return type of native function does not match return type of native java method", at,
                        "Native functions cannot use primitive types like 'int'. Use the class java.lang.Integer instead of 'int' for example, to allow for nullable types");
                return null;
            }
            if (!returnType.nullable() && returnType.getPrimitive() != PrimitiveDatatype.VOID && !invokeMethod.isAnnotationPresent(Nonnull.class)) {
                parser.parserError("Return type of native function must be nullable", at,
                        "Either add the 'nullable' modifier to your native function definition, or annotate the java method with " + Nonnull.class.getName());
                return null;
            }
            if (!java.lang.reflect.Modifier.isStatic(invokeMethod.getModifiers())) {
                parser.parserError("Native methods must be static", at);
                return null;
            }
            if (!invokeMethod.isAnnotationPresent(MiCallable.class)) {
                parser.parserError("May only use java methods annotated with " + MiCallable.class.getName() + " as native functions", at,
                        "Annotate the java method with " + MiCallable.class.getName());
                return null;
            }
            return invokeMethod;
        } catch (final Exception e) {
            parser.parserError("Unknown error when evaluating native java function: " + e.getClass().getName(), at,
                    "Native functions cannot use primitive types like 'int'. Use the class java.lang.Integer instead of 'int' for example, to allow for nullable types");
            return null;
        }
    }

    public Node evalIfStatement(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        final FunctionScope functionScope = expectFunctionScope(tokens.get(0));
        if (functionScope == null) return null;
        parser.scope(ScopeType.IF);
        return evalConditional(tokens, modifiers, NodeType.LITERAL_IF, false);
    }

    public Node evalWhileStatement(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers, final boolean unscoped) {
        final FunctionScope functionScope = expectFunctionScope(tokens.get(0));
        if (functionScope == null) return null;
        if (!unscoped) parser.scope(ScopeType.WHILE);
        return evalConditional(tokens, modifiers, NodeType.LITERAL_WHILE, unscoped);
    }

    public Node evalDoStatement(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        final FunctionScope functionScope = expectFunctionScope(tokens.get(0));
        if (functionScope == null) return null;
        if (unexpectedModifiers(modifiers)) return null;
        final Token doToken = parser.getAndExpect(tokens, 0, NodeType.LITERAL_DO);
        final Token scopeToken = parser.getAndExpect(tokens, 1, NodeType.LBRACE);
        if (Parser.anyNull(doToken, scopeToken)) return null;

        parser.scope(ScopeType.DO);
        return new Node(NodeType.DO_STATEMENT);
    }

    public Node evalForStatement(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        final FunctionScope functionScope = expectFunctionScope(tokens.get(0));
        if (functionScope == null) return null;
        if (unexpectedModifiers(modifiers)) return null;
        final List<List<Token>> exprs = splitByComma(tokens.subList(1, tokens.size() - 1));
        if (exprs.isEmpty()) {
            parser.parserError("Expected expression after 'for' statement");
            return null;
        }
        return switch (exprs.size()) {
            case 2 -> evalTransitionalForStatement(exprs);
            case 3 -> evalTraditionalForStatement(exprs);
            default -> {
                final int atIndex = exprs.subList(0, exprs.size() - 1).stream().map(l -> l.size() + 1).reduce(0, Integer::sum) - 1;
                final Token at = tokens.get(atIndex);
                parser.parserError("Unexpected token '" + at.token() + "'", at, "Expected 2 or 3 expressions, so remove any trailing ones, or add a second expression if you only have 1.");
                yield null;
            }
        };
    }

    // for mut? i = 0, i < 10, i++
    public Node evalTraditionalForStatement(@NotNull final List<List<Token>> exprs) {
        final FunctionScope functionScope = expectFunctionScope(exprs.get(0).get(0));
        if (functionScope == null) return null;
        parser.scope(ScopeType.FAKE);
        parser.scopeIndent++;
        parser.actualIndent++;
        final Node createVariable = parser.evalUnscoped(exprs.get(0), NodeType.of(exprs.get(0).get(0)), Collections.emptyList());

        if (createVariable == null) return null;
        if (createVariable.type() != NodeType.VAR_DEF_AND_SET_VALUE) {
            parser.parserError("Expected variable definition", exprs.get(0).get(0));
            return null;
        }
        addLocalVarFromResult(createVariable);

        final ValueParser.TypedNode condition = parseExpression(exprs.get(1).subList(0, exprs.get(1).size() - 1));

        if (condition == null || condition.type() == null) return null;
        if (!condition.type().equals(Datatype.BOOL)) {
            parser.parserError("Expected boolean condition", "Cast condition to 'bool' or change the condition to be a bool on its own");
            return null;
        }

        final Node loopStatement = parser.evalUnscoped(exprs.get(2), NodeType.of(exprs.get(2).get(0)), Collections.emptyList());

        if (loopStatement == null) return null;
        if (loopStatement.type() != NodeType.VAR_SET_VALUE && loopStatement.type() != NodeType.FUNCTION_CALL) {
            parser.parserError("Expected variable set or function call as for loop instruct", exprs.get(0).get(0));
            return null;
        }
        parser.scope(ScopeType.FOR);
        return new Node(NodeType.FOR_FAKE_SCOPE, parser.currentToken().actualLine(),
                new Node(NodeType.FOR_STATEMENT, parser.currentToken().actualLine(),
                        createVariable,
                        new Node(NodeType.CONDITION, -1, condition.node()),
                        new Node(NodeType.FOR_INSTRUCT, -1, loopStatement)
                )
        );
    }

    // for mut? i = 0, i -> 10 // i goes in a transition to 10, exactly the same as above example but written differently
    public Node evalTransitionalForStatement(@NotNull final List<List<Token>> exprs) {
        final List<Token> transition = exprs.get(1);
        final Token identifier = parser.getAndExpect(transition, 0, NodeType.IDENTIFIER);
        final Token arrow = parser.getAndExpect(transition, 1, NodeType.DOUBLE_DOT);
        if (Parser.anyNull(arrow, identifier)) return null;

        final List<Token> val = transition.subList(2, transition.size());

        final List<Token> condition = new ArrayList<>(Arrays.asList(identifier, Token.of("<"), Token.of("(")));
        condition.addAll(val);
        condition.addAll(Arrays.asList(Token.of(")"), Token.of(";"))); // semicolon needed as splitByComma() automatically puts those & evalTraditionalForStatement thinks there always is a semicolon

        return evalTraditionalForStatement(Arrays.asList(
                exprs.get(0),
                condition,
                Arrays.asList(identifier, Token.of("++"))
        ));
    }

    private List<List<Token>> splitByComma(@NotNull final List<Token> tokens) {
        final List<List<Token>> result = new ArrayList<>();
        List<Token> current = new ArrayList<>();

        for (@NotNull final Token token : tokens) {
            if (NodeType.of(token) == NodeType.COMMA) {
                current.add(Token.of(";"));
                result.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(token);
        }
        if (!current.isEmpty()) result.add(current);
        return result;
    }

    private Node evalConditional(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers, @NotNull final NodeType condType, final boolean unscoped) {
        if (unexpectedModifiers(modifiers)) return null;
        final Token first = parser.getAndExpect(tokens, 0, condType);
        if (Parser.anyNull(first)) return null;

        final ValueParser.TypedNode expr = parseExpression(tokens.subList(1, tokens.size() - 1));
        if (expr == null) return null;

        if (!Datatype.equal(expr.type(), Datatype.BOOL)) {
            parser.parserError("Expected boolean condition after '" + condType.getAsString() + "', but got " + expr.type() + " instead", "Cast condition to 'bool' or change the expression to be a bool on its own");
            return null;
        }
        return new Node(NodeType.valueOf(condType.getAsString().toUpperCase() + "_STATEMENT" + (unscoped ? "_UNSCOPED" : "")), first.actualLine(),
                new Node(NodeType.CONDITION, first.actualLine(),
                        new Node(NodeType.VALUE, first.actualLine(), expr.node())
                )
        );
    }

    private boolean unexpectedModifiers(@NotNull final List<Node> modifiers) {
        if (!modifiers.isEmpty()) {
            final Token firstModif = modifiers.stream().map(Node::value).findFirst().orElse(null);
            parser.unexpected(firstModif);
            return true;
        }
        return false;
    }

    public Node evalUseStatement(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;
        final FunctionScope functionScope = expectFunctionScope(tokens.get(0));
        if (functionScope == null) return null;

        final Token use = parser.getAndExpect(tokens, 0, NodeType.LITERAL_USE);
        final Token identifier = parser.getAndExpect(tokens, 1, NodeType.IDENTIFIER);
        final Token semi = parser.getAndExpect(tokens, 2, NodeType.SEMI);
        if (Parser.anyNull(use, identifier, semi)) return null;

        final String moduleName = identifier.token();

        final Optional<Module> module = parser.findModuleFromIdentifier(moduleName, identifier, true);
        if (module.isEmpty()) return null;

        functionScope.using(moduleName);
        return new Node(NodeType.USE_STATEMENT, identifier.actualLine(), new Node(NodeType.IDENTIFIER, identifier));
    }

    public Node evalModuleDefinition(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;

        final Token identifier = parser.getAndExpect(tokens, 1, NodeType.IDENTIFIER);
        final Token lbrace = parser.getAndExpect(tokens, 2, NodeType.LBRACE);
        if (Parser.anyNull(lbrace, identifier)) return null;

        if (expectModuleOrRoot(IdentifierType.MODULE)) return null;
        parser.scope(ScopeType.MODULE);
        if (handleRestrictedName(identifier)) return null;
        if (!parser.skimming) parser.currentParsingModule = parser.currentParsingModule.subModules().stream().filter(m -> m.name().equals(identifier.token())).findFirst().orElse(null);

        return new Node(NodeType.CREATE_MODULE, identifier.actualLine(),
                new Node(NodeType.IDENTIFIER, identifier)
        );
    }

    public Node evalEnumDefinition(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        final Token identifier = parser.getAndExpect(tokens, 1, NodeType.IDENTIFIER);
        final Token lbrace = parser.getAndExpect(tokens, 2, NodeType.LBRACE);
        if (Parser.anyNull(lbrace, identifier)) return null;

        if (expectModuleOrRoot(IdentifierType.ENUM)) return null;
        final Optional<Node> firstMutabilityModif = modifiers.stream().filter(m -> m.type().isMutabilityModifier()).findFirst();
        if (firstMutabilityModif.isPresent()) {
            parser.parserError("Cannot declare enums as own, const or mut, they are automatically constant because they cannot be redefined",
                    firstMutabilityModif.get().value());
            return null;
        }
        final Optional<Scope> oldScope = parser.scope();
        if (oldScope.isEmpty()) {
            parser.parserError("Unexpected parsing error, null scope before adding an enum", identifier);
            return null;
        }
        if (parser.skimming && !parser.stdlib && (oldScope.get().type() != ScopeType.MODULE)) {
            parser.parserError("Cannot define enums at root level", identifier,
                    "Move your enum into a module or create a new module for it");
            return null;
        }

        parser.scope(ScopeType.ENUM);
        final Optional<Scope> newScope = parser.scope();
        if (newScope.isEmpty()) {
            parser.parserError("Unexpected parsing error, null scope after adding an enum", identifier);
            return null;
        }
        final EnumScope enumScope = (EnumScope) newScope.get();
        enumScope.modifiers(modifiers.stream().map(n -> Modifier.of(n.type())).collect(Collectors.toList()));
        enumScope.name(identifier.token());
        enumScope.module(parser.currentParsingModule == null ? parser.lastModule() : parser.currentParsingModule);

        return new Node(NodeType.CREATE_ENUM, identifier.actualLine(),
                new Node(NodeType.IDENTIFIER, identifier),
                new Node(NodeType.MODIFIERS, modifiers)
        );
    }

    private boolean expectModuleOrRoot(@NotNull final IdentifierType type) {
        final Optional<Scope> current = parser.scope();
        if (parser.skimming) {
            if (current.isEmpty()) {
                parser.parserError("Unexpected parsing error, could not create " + type.name().toLowerCase() + " at root level");
                return true;
            }
            final ScopeType currentType = current.get().type();
            if (currentType != ScopeType.PARENT && currentType != ScopeType.MODULE) {
                parser.parserError("Expected " + type.name().toLowerCase() + " definition to be at root level or inside of another module");
                return true;
            }
        }
        return false;
    }

    public Node evalStdLibFinish(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        if (unexpectedModifiers(modifiers)) return null;

        final Token stdlibFinish = parser.getAndExpect(tokens, 0, NodeType.STANDARDLIB_MI_FINISH_CODE);
        final Token semi = parser.getAndExpect(tokens, 1, NodeType.SEMI);
        if (stdlibFinish == null || semi == null) return null;

        parser.stdlib = false;

        return new Node(NodeType.STANDARDLIB_MI_FINISH_CODE);
    }

    public FunctionScope expectFunctionScope(@NotNull final Token at) {
        final Optional<Scope> currentScope = parser.scope();
        if (currentScope.isEmpty() || !(currentScope.get() instanceof final FunctionScope functionScope)) {
            parser.parserError("Unexpected parsing error, expected statement to be inside of a function", at, "Enclose your statement inside of a function");
            return null;
        }
        return functionScope;
    }

    public LoopScope expectLoopScope(@NotNull final Token at) {
        final Optional<Scope> currentScope = parser.scope();
        if (currentScope.isEmpty() || !(currentScope.get() instanceof final LoopScope loopScope)) {
            parser.parserError("Unexpected parsing error, expected statement to be inside of a loop (while or for)", at, "Delete unavailable statement");
            return null;
        }
        return loopScope;
    }

    private EnumScope expectEnumScope(@NotNull final Token at) {
        final Optional<Scope> currentScope = parser.scope();
        if (currentScope.isEmpty() || !(currentScope.get() instanceof final EnumScope enumScope)) {
            parser.parserError("Unexpected parsing error, expected statement to be inside of an enum", at, "Enclose your statement inside of an enum");
            return null;
        }
        return enumScope;
    }

    public Node evalClassDefinition(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        final Token identifier = parser.getAndExpect(tokens, 1, NodeType.IDENTIFIER);
        final Token lbrace = parser.getAndExpect(tokens, 2, NodeType.LBRACE);
        if (Parser.anyNull(lbrace, identifier)) return null;

        if (expectModuleOrRoot(IdentifierType.CLASS)) return null;
        final Optional<Node> firstMutabilityModif = modifiers.stream().filter(m -> m.type().isMutabilityModifier()).findFirst();
        if (firstMutabilityModif.isPresent()) {
            parser.parserError("Cannot declare classes as own, const or mut, they are automatically constant because they cannot be redefined",
                    firstMutabilityModif.get().value());
            return null;
        }
        final Optional<Scope> oldScope = parser.scope();
        if (oldScope.isEmpty()) {
            parser.parserError("Unexpected parsing error, null scope before adding a class", identifier);
            return null;
        }
        if (parser.skimming && !parser.stdlib && (oldScope.get().type() != ScopeType.MODULE)) {
            parser.parserError("Cannot define classes at root level", identifier,
                    "Move your class into a module or create a new module for it");
            return null;
        }

        parser.scope(ScopeType.CLASS);
        final Optional<Scope> newScope = parser.scope();
        if (newScope.isEmpty() || !(newScope.get() instanceof final ClassScope classScope)) {
            parser.parserError("Unexpected parsing error, invalid scope after adding a class", identifier);
            return null;
        }
        classScope.module(parser.lastModule());
        classScope.name(identifier.token());
        parser.currentClass = classScope;

        return new Node(NodeType.CREATE_CLASS, identifier.actualLine(),
                new Node(NodeType.IDENTIFIER, identifier),
                new Node(NodeType.MODIFIERS, modifiers)
        );
    }

    public Node evalNewStatement(@NotNull final List<Token> tokens, @NotNull final List<Node> modifiers) {
        final Token newToken = parser.getAndExpect(tokens, 0, NodeType.LITERAL_NEW);
        final Token parenOpen = parser.getAndExpect(tokens, 1, NodeType.LPAREN);
        if (Parser.anyNull(newToken, parenOpen)) return null;

        final Optional<Scope> current = parser.scope();
        if (current.isEmpty() || !(current.get() instanceof final ClassScope classScope)) {
            parser.parserError("Expected constructor to be inside of a class", newToken);
            return null;
        }

        final Optional<Node> firstMutabilityModif = modifiers.stream().filter(m -> m.type().isMutabilityModifier()).findFirst();
        if (firstMutabilityModif.isPresent()) {
            parser.parserError("Cannot declare classes as own, const or mut, they are automatically constant because they cannot be redefined",
                    firstMutabilityModif.get().value());
            return null;
        }
        final List<Node> paramNodes = parseParametersDefineFunction(tokens.subList(2, tokens.size() - 2));
        parser.scope(ScopeType.FUNCTION);
        final Optional<Scope> funcScope = parser.scope();
        if (funcScope.isPresent() && funcScope.get() instanceof final FunctionScope functionScope) {
            for (final Node param : paramNodes) {
                final List<Modifier> modifs = param.child(2).children().stream().map(n -> Modifier.of(n.type())).toList();
                final Datatype pType = Datatype.of(parser, param.child(0).value(), modifs.contains(Modifier.NULLABLE));
                if (pType == null) return null;
                final String ident = param.child(1).value().token();
                functionScope.addLocalVariable(parser, new LocalVariable(
                        new Variable(
                                ident, pType, modifs, null, true, null
                        ), functionScope
                ), param.child(0).value());
            }
            parser.currentFuncReturnType = Datatype.VOID;
        }
        if (!parser.skimming) {
            final List<Modifier> modifiersNew = modifiers.stream().map(n -> Modifier.of(n.type())).toList();

            final List<FunctionParameter> params = paramNodes.stream().map(n -> {
                final List<Modifier> modifs = n.child(2).children().stream().map(n2 -> Modifier.of(n2.type())).toList();
                final Datatype datatype = Datatype.of(parser, n.child(0).value(), modifs.contains(Modifier.NULLABLE));
                if (datatype == null) throw new NullPointerException();
                return new FunctionParameter(
                        datatype,
                        n.child(1).value().token(),
                        modifs
                );
            }).toList();

            final FunctionDefinition def = new FunctionDefinition("new", Datatype.VOID, params, modifiersNew, parser.currentParsingModule, false);
            classScope.constructor(parser, def, newToken);
        }
        return new Node(NodeType.CREATE_CONSTRUCTOR, newToken.actualLine(),
                new Node(NodeType.MODIFIERS, modifiers),
                new Node(NodeType.PARAMETERS, paramNodes)
        );
    }

}
