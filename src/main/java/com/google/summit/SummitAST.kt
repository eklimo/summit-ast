/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.summit

import com.google.common.base.Ascii
import com.google.common.flogger.FluentLogger
import com.google.summit.ast.CompilationUnit
import com.google.summit.translation.Translate
import com.nawforce.apexparser.ApexLexer
import com.nawforce.apexparser.ApexParser
import com.nawforce.apexparser.CaseInsensitiveInputStream
import java.nio.file.Files
import java.nio.file.Path
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

/** Interface to the Summit AST library. */
object SummitAST {
  /** Name provided to [parseAndTranslate] for string inputs. */
  private const val STRING_INPUT = "<str>"

  private val logger = FluentLogger.forEnclosingClass()

  /** Listener for syntax errors that keeps a total count. */
  class SyntaxErrorListener : BaseErrorListener() {
    var numErrors = 0

    override fun syntaxError(
      recognizer: Recognizer<*, *>,
      offendingSymbol: Any?,
      line: Int,
      charPositionInLine: Int,
      msg: String,
      e: RecognitionException?
    ) {
      this.numErrors += 1
      logger.atInfo().log("Syntax error at %d:%d: %s", line, charPositionInLine, msg)
    }
  }

  /** The type of top-level declaration in an input. */
  enum class CompilationType {
    CLASS,
    TRIGGER
  }

  /**
   * Parses and translates the file at [path] and returns a [CompilationUnit] representing the AST
   * if the operation was successful.
   */
  fun parseAndTranslate(path: Path) =
    parseAndTranslate(
      name = path.toString(),
      type =
        when {
          isApexClassFile(path) -> CompilationType.CLASS
          isApexTriggerFile(path) -> CompilationType.TRIGGER
          else -> throw IllegalArgumentException("Unexpected file type")
        },
      charStream = CharStreams.fromPath(path),
    )

  /**
   * Parses and translates the [string] as [type] and returns a [CompilationUnit] representing the
   * AST if the operation was successful.
   */
  fun parseAndTranslate(string: String, type: CompilationType) =
    parseAndTranslate(
      name = STRING_INPUT,
      type = type,
      charStream = CharStreams.fromString(string),
    )

  /**
   * Parses and translates the [charStream] and returns a [CompilationUnit] representing the AST if
   * the operation was successful.
   */
  internal fun parseAndTranslate(
    name: String,
    type: CompilationType,
    charStream: CharStream
  ): CompilationUnit? {
    // Apex is a case-insensitive language and the grammar is
    // defined to operate on fully lower-cased inputs.
    val lexer = ApexLexer(CaseInsensitiveInputStream(charStream))
    val tokens = CommonTokenStream(lexer)
    val parser = ApexParser(tokens)

    val errorCounter = SyntaxErrorListener()
    lexer.addErrorListener(errorCounter)
    parser.addErrorListener(errorCounter)

    // Do parse as complete compilation unit
    val tree =
      when (type) {
        CompilationType.CLASS -> parser.compilationUnit()
        CompilationType.TRIGGER -> parser.triggerUnit()
      }

    if (errorCounter.numErrors > 0) {
      logger.atWarning().log("Failed to parse %s", name)
      return null // failure
    }

    try {
      val translator = Translate(name, tokens)
      val ast = translator.translate(tree)
      return ast
    } catch (e: Translate.TranslationException) {
      logger.atWarning().withCause(e).log("Failed to translate %s", name)
      return null // failure
    }
  }

  /**
   * Returns `true` if the path is an Apex class file. These are regular files with the
   * (case-insensitive) suffix `.cls`.
   */
  private fun isApexClassFile(path: Path): Boolean =
    Files.isRegularFile(path) && Ascii.toLowerCase(path.toString()).endsWith(".cls")

  /**
   * Returns `true` if the path is an Apex trigger file. These are regular files with the
   * (case-insensitive) suffix `.trigger`.
   */
  private fun isApexTriggerFile(path: Path): Boolean =
    Files.isRegularFile(path) && Ascii.toLowerCase(path.toString()).endsWith(".trigger")

  /** Returns `true` if the path is an Apex source file: either a class or a trigger. */
  fun isApexSourceFile(path: Path): Boolean = isApexClassFile(path) || isApexTriggerFile(path)
}