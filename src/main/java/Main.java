import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(https?|ftp|file|mailto):[^\\s]+");
    public static void main(String[] args) throws IOException {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", exchange -> {
            try (InputStream is = Main.class.getClassLoader().getResourceAsStream("index.html")) {
                if (is == null) {
                    sendHtml(exchange, "<h2>index.html не найден в ресурсах!</h2>");
                    return;
                }
                byte[] html = is.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, html.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(html);
                }
            }
        });

        server.createContext("/upload", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                byte[] data = exchange.getRequestBody().readAllBytes();
                String body = new String(data, StandardCharsets.ISO_8859_1);

                // простая обработка multipart/form-data
                String boundary = body.lines().findFirst().orElse("");
                int filenameIdx = body.indexOf("filename=\"");
                String filename = "file.xlsx";
                if (filenameIdx > 0) {
                    int start = filenameIdx + 10;
                    int end = body.indexOf("\"", start);
                    filename = body.substring(start, end);
                }

                String lowerName = filename.toLowerCase(Locale.ROOT);
                if (!(lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx"))) {
                    sendHtml(exchange, "<h2>Ошибка: можно загружать только .xls или .xlsx</h2>");
                    return;
                }

                int fileStart = body.indexOf("\r\n\r\n") + 4;
                int fileEnd = body.lastIndexOf(boundary) - 4;
                if (fileStart <= 0 || fileEnd <= fileStart)
                    throw new IOException("Не удалось извлечь файл из запроса.");

                byte[] fileBytes = body.substring(fileStart, fileEnd)
                        .getBytes(StandardCharsets.ISO_8859_1);

                StringBuilder result = new StringBuilder("""
                    <!DOCTYPE html>
                    <html lang="ru">
                    <head>
                        <meta charset="UTF-8">
                        <title>Результат Excel</title>
                        <style>
                            body { font-family: sans-serif; margin: 2em; }
                            table { border-collapse: collapse; margin-top: 1em; }
                            td, th { border: 1px solid #ccc; padding: 6px 10px; }
                            button.copy { margin-left: 8px; }
                        </style>
                        <script>
                        function copyToClipboard(text) {
                            navigator.clipboard.writeText(text)
                                .then(() => console.log('Скопировано: ' + text))
                                .catch(err => console.error('Ошибка копирования: ' + err));
                        }
                        </script>
                    </head>
                    <body>
                    <h1>Содержимое Excel</h1>
                    <table>
                """);

                try (InputStream is = new ByteArrayInputStream(fileBytes);
                     Workbook workbook = createWorkbook(is, lowerName)) {
                    Sheet sheet = workbook.getSheetAt(0);
                    for (Row row : sheet) {
                        result.append("<tr>");
                        for (Cell cell : row) {
                            String value = getCellValue(cell);
                            result.append("<td>");
                            Matcher matcher = URL_PATTERN.matcher(value);
                            int lastEnd = 0;
                            while (matcher.find()) {
                                String url = matcher.group();
                                result.append(value.substring(lastEnd, matcher.start()));
                                result.append("<a href='").append(url).append("' target='_blank'>")
                                      .append(url).append("</a>")
                                      .append("<button class='copy' onclick=\"copyToClipboard('")
                                      .append(escapeJs(url)).append("')\">📋</button>");
                                lastEnd = matcher.end();
                            }
                            result.append(value.substring(lastEnd));
                            result.append("</td>");
                        }
                        result.append("</tr>");
                    }
                }

                result.append("</table></body></html>");
                sendHtml(exchange, result.toString());

            } catch (Exception e) {
                sendHtml(exchange, "<h2>Ошибка при обработке файла: " + e.getMessage() + "</h2>");
            }
        });

        System.out.println("🚀 Server started at http://localhost:" + port);
        server.start();
    }

    private static Workbook createWorkbook(InputStream is, String filename) throws IOException {
        if (filename.endsWith(".xlsx")) return new XSSFWorkbook(is);
        if (filename.endsWith(".xls")) return new HSSFWorkbook(is);
        throw new IOException("Неподдерживаемый формат: " + filename);
    }

    private static String getCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> (DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toString()
                    : Double.toString(cell.getNumericCellValue()));
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    private static String escapeJs(String text) {
        return text.replace("'", "\\'").replace("\"", "\\\"");
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] response = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
}
