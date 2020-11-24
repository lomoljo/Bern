// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.starlark.java.cmd;

import java.io.FileOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.time.Duration;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import javax.annotation.Nullable;

/**
 * Main is a standalone interpreter for the core Starlark language. It does not yet support load
 * statements.
 *
 * <p>The sad class name is due to the linting tool, which forbids lowercase "starlark", and Java's
 * lack of renaming imports, which makes the name "Starlark" impractical due to conflicts with
 * eval.Starlark.
 */
class Main {
  private static final String START_PROMPT = ">> ";
  private static final String CONTINUATION_PROMPT = ".. ";

  private static final StarlarkThread thread;
  private static final Module module = Module.create();

  // TODO(adonovan): set load-binds-globally option when we support load,
  // so that loads bound in one REPL chunk are visible in the next.
  private static final FileOptions OPTIONS = FileOptions.DEFAULT;

  static {
    Mutability mu = Mutability.create("interpreter");
    thread = new StarlarkThread(mu, StarlarkSemantics.DEFAULT);
    thread.setPrintHandler((th, msg) -> System.out.println(msg));
  }

  @Nullable
  private static String prompt(LineReader reader) {
    while (true) {
      StringBuilder input = new StringBuilder();
      String prompt = START_PROMPT;
      try {
        String lineSeparator = "";
        while (true) {
          String line = reader.readLine(prompt);
          if (line.isEmpty()) {
            return input.toString();
          }
          input.append(lineSeparator).append(line);
          lineSeparator = "\n";
          prompt = CONTINUATION_PROMPT;
        }
      } catch (UserInterruptException unused) {
        System.err.println("KeyboardInterrupt");
      } catch (EndOfFileException unused) {
        return null;
      }
    }
  }

  @Nullable
  private static LineReader buildLineReader() {
    try {
      Terminal terminal = TerminalBuilder.terminal();
      LineReaderBuilder builder = LineReaderBuilder.builder().terminal(terminal);
      builder.terminal(terminal);
      // By default jline moves cursor for a short time to the matching paren
      builder.variable(LineReader.BLINK_MATCHING_PAREN, false);
      String userHome = System.getProperty("user.home");
      if (userHome != null) {
        // Persist history by default
        builder.variable(LineReader.HISTORY_FILE, userHome + "/.starlark_java_history");
      }
      return builder.build();
    } catch (IOException e) {
      System.err.println("Failed to initialize terminal: " + e.getMessage());
      return null;
    }
  }

  /** Provide a REPL evaluating Starlark code. */
  @SuppressWarnings("CatchAndPrintStackTrace")
  private static int readEvalPrintLoop() {
    System.err.println("Welcome to Starlark (java.starlark.net)");
    String line;

    // TODO(adonovan): parse a compound statement, like the Python and
    // go.starlark.net REPLs. This requires a new grammar production, and
    // integration with the lexer so that it consumes new
    // lines only until the parse is complete.

    LineReader reader = buildLineReader();
    if (reader == null) {
      return 1;
    }
    try {
      while ((line = prompt(reader)) != null) {
        ParserInput input = ParserInput.fromString(line, "<stdin>");
        try {
          Object result = Starlark.execFile(input, OPTIONS, module, thread);
          if (result != Starlark.NONE) {
            System.out.println(Starlark.repr(result));
          }
        } catch (SyntaxError.Exception ex) {
          for (SyntaxError error : ex.errors()) {
            System.err.println(error);
          }
        } catch (EvalException ex) {
          // TODO(adonovan): provide a SourceReader. Requires that we buffer the
          // entire history so that line numbers don't reset in each chunk.
          System.err.println(ex.getMessageWithStack());
        } catch (InterruptedException ex) {
          System.err.println("Interrupted");
        }
      }
      return 0;
    } catch (IOError e) {
      // jline throws IOError: https://github.com/jline/jline3/issues/608
      System.err.println("I/O error: " + e.getMessage());
      return 1;
    } finally {
      try {
        reader.getTerminal().close();
      } catch (IOException unused) {
      }
    }
  }

  /** Execute a Starlark file. */
  private static int execute(ParserInput input) {
    try {
      Starlark.execFile(input, OPTIONS, module, thread);
      return 0;
    } catch (SyntaxError.Exception ex) {
      for (SyntaxError error : ex.errors()) {
        System.err.println(error);
      }
      return 1;
    } catch (EvalException ex) {
      System.err.println(ex.getMessageWithStack());
      return 1;
    } catch (InterruptedException e) {
      System.err.println("Interrupted");
      return 1;
    }
  }

  public static void main(String[] args) throws IOException {
    String file = null;
    String cmd = null;
    String cpuprofile = null;

    // parse flags
    int i;
    for (i = 0; i < args.length; i++) {
      if (!args[i].startsWith("-")) {
        break;
      }
      if (args[i].equals("--")) {
        i++;
        break;
      }
      if (args[i].equals("-c")) {
        if (i + 1 == args.length) {
          throw new IOException("-c <cmd> flag needs an argument");
        }
        cmd = args[++i];
      } else if (args[i].equals("-cpuprofile")) {
        if (i + 1 == args.length) {
          throw new IOException("-cpuprofile <file> flag needs an argument");
        }
        cpuprofile = args[++i];
      } else {
        throw new IOException("unknown flag: " + args[i]);
      }
    }
    // positional arguments
    if (i < args.length) {
      if (i + 1 < args.length) {
        throw new IOException("too many positional arguments");
      }
      file = args[i];
    }

    if (cpuprofile != null) {
      FileOutputStream out = new FileOutputStream(cpuprofile);
      Starlark.startCpuProfile(out, Duration.ofMillis(10));
    }

    int exit;
    if (file == null) {
      if (cmd != null) {
        exit = execute(ParserInput.fromString(cmd, "<command-line>"));
      } else {
        exit = readEvalPrintLoop();
      }
    } else if (cmd == null) {
      try {
        exit = execute(ParserInput.readFile(file));
      } catch (IOException e) {
        // This results in such lame error messages as:
        // "Error reading a.star: java.nio.file.NoSuchFileException: a.star"
        System.err.format("Error reading %s: %s\n", file, e);
        exit = 1;
      }
    } else {
      System.err.println("usage: Starlark [-cpuprofile file] [-c cmd | file]");
      exit = 1;
    }

    if (cpuprofile != null) {
      Starlark.stopCpuProfile();
    }

    System.exit(exit);
  }
}
