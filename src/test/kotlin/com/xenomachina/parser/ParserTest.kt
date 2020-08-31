// Copyright © 2017 Laurence Gonsalves
//
// This file is part of kessel, a library which can be found at
// http://github.com/xenomachina/kessel
//
// This library is free software; you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by the
// Free Software Foundation; either version 2.1 of the License, or (at your
// option) any later version.
//
// This library is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
// for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with this library; if not, see http://www.gnu.org/licenses/

package com.xenomachina.parser

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Validated
import com.xenomachina.chain.Chain
import io.kotlintest.matchers.shouldEqual
import io.kotlintest.matchers.shouldThrow
import io.kotlintest.specs.FunSpec

private fun tokens(s: String) =
    MATH_TOKENIZER.tokenize(CharOffsetTracker, s)
        .map { it.value }
        .filter { !(it is MathToken.Space) }

sealed class Expr {
    data class Op(val left: Expr, val op: MathToken.Operator, val right: Expr) : Expr()
    data class Leaf(val value: MathToken.Value) : Expr()
}

fun <E, A> Validated<E, A>.assertValid(): A = (this as Validated.Valid<A>).a
fun <E, A> Validated<E, A>.assertInvalid(): E = (this as Validated.Invalid<E>).e

fun <T> Chain<T>.assertHead(): T = (this as Chain.NonEmpty<T>).head

class ParserTest : FunSpec({
    test("simple") {
        val parser = Parser.Builder {
            seq(isA<MathToken.Value.IntLiteral>(), END_OF_INPUT) { integer, _ -> integer.value.toInt() }
        }.build()

        parser.parse(tokens("5")) shouldEqual Validated.Valid(5)

        parser.parse(tokens("hello")).assertInvalid().head.message
                .shouldEqual("Unexpected: Identifier(name=hello)")
    }

    test("epsilon") {
        val parser = Parser.Builder {
            seq(epsilon, END_OF_INPUT) { x, _ -> x }
        }.build()

        parser.parse(tokens("")) shouldEqual Validated.Valid(Unit)
    }

    test("optional") {
        val parser = Parser.Builder {
            seq(optional(isA<MathToken.Value.IntLiteral>().map { it.value }), END_OF_INPUT) { x, _ -> x }
        }.build()

        parser.parse(tokens("")) shouldEqual Validated.Valid(None)
        parser.parse(tokens("512")) shouldEqual Validated.Valid(Option.just(512))
    }

    test("either") {
        val parser = Parser.Builder {
            seq(
                either(
                    isA<MathToken.Value.IntLiteral>().map { it.value },
                    isA<MathToken.Value.Identifier>().map { it.name }
                ),
                END_OF_INPUT) { x, _ -> x }
        }.build()

        parser.parse(tokens("978136")) shouldEqual Validated.Valid(Either.left(978136))
        parser.parse(tokens("hello")) shouldEqual Validated.Valid(Either.right("hello"))
    }

    test("repeat") {
        val parser = Parser.Builder {
            seq(
                Parser.Builder.repeat(isA<MathToken.Value.IntLiteral>().map { it.value }),
                END_OF_INPUT) { x, _ -> x }
        }.build()

        parser.parse(tokens("")) shouldEqual Validated.Valid(emptyList<Int>())

        parser.parse(tokens("42")) shouldEqual Validated.Valid(listOf(42))

        parser.parse(tokens("4 8 15 16 23 42")) shouldEqual Validated.Valid(
            listOf(4, 8, 15, 16, 23, 42))

        val parser2 = Parser.Builder {
            seq(
                Parser.Builder.repeat(isA<MathToken.Value.IntLiteral>().map { it.value }),
                isA<MathToken.Value.IntLiteral>().map { it.value },
                END_OF_INPUT) { x, y, _ -> Pair(x, y) }
        }.build()

        parser2.parse(tokens("")).assertInvalid()

        parser2.parse(tokens("42")) shouldEqual Validated.Valid(
            Pair(emptyList<Int>(), 42))

        parser2.parse(tokens("4 8 15 16 23 42 108")) shouldEqual Validated.Valid(
            Pair(listOf(4, 8, 15, 16, 23, 42), 108))

        // TODO: re-enable this when repeat doesn't abuse the stack
//        val million = parser.parse(
//            (1..1000000).map { MathToken.Value.IntLiteral(it) }.asSequence()
//        )
//        million.assertRight().size shouldEqual 1000000
    }

    test("repeat with separator") {
        // TODO
    }

    test("nullable is true") {
        // TODO: add test where nullable is true
    }

    test("expression") {

        lateinit var multRule: Rule<*, *>
        lateinit var exprRule: Rule<*, *>

        val parser = Parser.Builder {
            val grammar = object {
                val multOp = isA<MathToken.Operator.MultOp>()

                val addOp = isA<MathToken.Operator.AddOp>()

                val factor = oneOf(
                            isA<MathToken.Value.IntLiteral>().map(Expr::Leaf),
                            isA<MathToken.Value.Identifier>().map(Expr::Leaf),
                            seq(
                                    isA<MathToken.OpenParen>(),
                                    recur { expression },
                                    isA<MathToken.CloseParen>()
                            ) { _, expr, _ -> expr }
                    )

                val term: Rule<MathToken, Expr> by lazy {
                    oneOf(
                            factor,
                            seq(factor, multOp, recur { term }) { l, op, r -> Expr.Op(l, op, r) }
                    )
                }

                val expression: Rule<MathToken, Expr> by lazy {
                    oneOf(
                            term,
                            seq(term, addOp, recur { expression }) { l, op, r -> Expr.Op(l, op, r) }
                    )
                }

                val start = seq(expression, END_OF_INPUT) { expr, _ -> expr }
            }

            multRule = grammar.multOp
            exprRule = grammar.expression

            grammar.start
        }.build()

        parser.ruleProps[multRule]!!.nullable shouldEqual false
        parser.ruleProps[exprRule]!!.nullable shouldEqual false

        // TODO: make parser work with sequence of positioned thingies
        val parse = parser.parse(MATH_TOKENIZER.tokenize("5 * (3 + 7) - (4 / (2 - 1))")
            .filterNot { it is MathToken.Space })
        val expr = parse.assertValid()

        expr as Expr.Op
        expr.op as MathToken.Operator.AddOp
        expr.op.name shouldEqual "-"

        // 5 * (3 + 7)
        expr.left as Expr.Op
        expr.left.op as MathToken.Operator.MultOp
        expr.left.op.name shouldEqual "*"

        // 5
        expr.left.left as Expr.Leaf
        expr.left.left.value as MathToken.Value.IntLiteral
        expr.left.left.value.value shouldEqual 5

        // (3 + 7)
        expr.left.right as Expr.Op
        expr.left.right.op as MathToken.Operator.AddOp
        expr.left.right.op.name shouldEqual "+"
    }

    test("simple left recursion") {
        val parser = Parser.Builder {
            val grammar = object {
                val addOp = isA<MathToken.Operator.AddOp>()
                val number = isA<MathToken.Value.IntLiteral>().map(Expr::Leaf)

                val expression: Rule<MathToken, Expr> by lazy { oneOf<MathToken, Expr>(
                        number,
                        seq(recur { expression }, addOp, number) { l, op, r -> Expr.Op(l, op, r) }
                ) }
            }
            seq(grammar.expression, END_OF_INPUT) { expr, _ -> expr }
        }.build()

        shouldThrow<IllegalStateException> {
            parser.parse(tokens("1 + 2 + 3 + 4"))
        }.run {
            // Left-recursion is not currently supported.
            message shouldEqual "Left recursion detected"
        }
    }

    // TODO: support left recursion, and re-enable this test
//    test("left_recursion") {
//        val grammar = object {
//            val multOp = isA<MathToken.Operator.MultOp>()
//
//            val addOp = isA<MathToken.Operator.AddOp>()
//
//            val factor : Parser<MathToken, Expr> by lazy { oneOf<MathToken, Expr>(
//                    isA<MathToken.Value.IntLiteral>().map(Expr::Leaf),
//                    isA<MathToken.Value.Identifier>().map(Expr::Leaf),
//                    seq(
//                            isA<MathToken.OpenParen>(),
//                            L { expression },
//                            isA<MathToken.CloseParen>()
//                    ) { _, expr, _ -> expr }
//            ) }
//
//            val term : Parser<MathToken, Expr> by lazy { oneOf<MathToken, Expr>(
//                    factor,
//                    seq(L { term }, multOp, L { factor }) { l, op, r -> Expr.Op(l, op, r) }
//            ) }
//
//            val expression : Parser<MathToken, Expr> by lazy { oneOf<MathToken, Expr>(
//                    term,
//                    seq(L { expression }, addOp, L { term }) { l, op, r -> Expr.Op(l, op, r) }
//            ) }
//        }
//        val parser = seq(grammar.expression, endOfInput()) { expr, _ -> expr }
//        val ast = parser.parse(tokenChain("5 * (3 + 7) - (4 / (2 - 1))"))
//        ast.assertRight().javaClass shouldEqual Expr.Op::class
//    }
})
