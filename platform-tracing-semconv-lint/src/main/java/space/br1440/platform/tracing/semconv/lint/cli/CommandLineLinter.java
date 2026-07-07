package space.br1440.platform.tracing.semconv.lint.cli;

import space.br1440.platform.tracing.semconv.lint.LintReport;
import space.br1440.platform.tracing.semconv.lint.LintViolation;
import space.br1440.platform.tracing.semconv.lint.Linter;
import space.br1440.platform.tracing.semconv.lint.PlatformSpec;
import space.br1440.platform.tracing.semconv.lint.SpanRecord;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI-запуск линтера. Используется в CI-пайплайне для проверки экспортированных в JSON span'ов.
 * <p>
 * Аргументы:
 * <ul>
 *   <li>{@code --input <file>} — путь к JSON-файлу со span'ами (массив или JSONL); обязателен.</li>
 *   <li>{@code --quiet} — печатать только нарушения; по умолчанию печатается также сводка.</li>
 *   <li>{@code --warnings-as-errors} — приравнивать предупреждения к ошибкам в exit code.</li>
 * </ul>
 * Коды возврата:
 * <ul>
 *   <li>0 — нарушений уровня ERROR не найдено;</li>
 *   <li>1 — найдены нарушения уровня ERROR (или WARNING при включённом флаге);</li>
 *   <li>2 — ошибка использования (неверные аргументы / не удалось прочитать файл).</li>
 * </ul>
 */
public final class CommandLineLinter {

    private final PrintStream out;
    private final PrintStream err;

    public CommandLineLinter(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        int exit = new CommandLineLinter(System.out, System.err).run(args);
        System.exit(exit);
    }

    public int run(String[] args) {
        Path inputPath = null;
        boolean quiet = false;
        boolean warningsAsErrors = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--input" -> {
                    if (i + 1 >= args.length) {
                        return usageError("--input требует значения");
                    }
                    inputPath = Path.of(args[++i]);
                }
                case "--quiet" -> quiet = true;
                case "--warnings-as-errors" -> warningsAsErrors = true;
                case "--help", "-h" -> {
                    printUsage(out);
                    return 0;
                }
                default -> {
                    return usageError("неизвестный аргумент: " + arg);
                }
            }
        }

        if (inputPath == null) {
            return usageError("требуется аргумент --input <file>");
        }

        List<SpanRecord> spans;
        try {
            spans = new SpanJsonReader().readFile(inputPath);
        } catch (IOException e) {
            err.println("Не удалось прочитать файл: " + e.getMessage());
            return 2;
        }

        Linter linter = PlatformSpec.defaultLinter();
        LintReport report = linter.lint(spans);

        for (LintViolation violation : report.violations()) {
            out.println(formatViolation(violation));
        }

        if (!quiet) {
            out.println(String.format("Проверено span'ов: %d, ошибок: %d, предупреждений: %d",
                    report.spansChecked(), report.errorCount(), report.warningCount()));
        }

        if (report.errorCount() > 0) {
            return 1;
        }
        if (warningsAsErrors && report.warningCount() > 0) {
            return 1;
        }
        return 0;
    }

    private int usageError(String message) {
        err.println("Ошибка: " + message);
        printUsage(err);
        return 2;
    }

    private static void printUsage(PrintStream stream) {
        stream.println("Использование: platform-tracing-semconv-lint --input <file> [--quiet] [--warnings-as-errors]");
        stream.println();
        stream.println("  --input <file>            JSON-файл со span'ами (массив или JSONL).");
        stream.println("  --quiet                   Не печатать сводку.");
        stream.println("  --warnings-as-errors      Приравнивать WARNING к ERROR в exit code.");
        stream.println("  --help                    Показать эту справку.");
    }

    private static String formatViolation(LintViolation violation) {
        return String.format("[%s] %s — span '%s': %s",
                violation.severity(), violation.ruleId(), violation.spanName(), violation.message());
    }
}
