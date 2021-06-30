package it.vitalegi.haranalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class AppRunner implements CommandLineRunner {
    private static final String SEPARATOR = "\t";

    Logger log = LoggerFactory.getLogger(AppRunner.class);

    @Override
    public void run(String... args) throws Exception {
        log.info("Start");

        Stream<Path> files = getFiles();
        ArrayNode result = files.map(this::extractInfo)
                .reduce(this.newArray(), ArrayNode::addAll);
        applyRelativeTime(result);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("results.csv"))) {
            log.info("Export");
            List<String> headers = getHeaders(result);
            writer.append(headers.stream().collect(Collectors.joining(SEPARATOR, "", "\n")));
            export(writer, result, headers);
            log.info("Export done, {} entries", result.size());
        }

        files = getFiles();
        ArrayNode actions = files.map(this::extractInfo).map(fileContent -> {
            applyRelativeTime(fileContent);
            return identifyActions(fileContent, (ArrayNode) readFile(Paths.get("config.json")));
        }).reduce(this.newArray(), ArrayNode::addAll);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter("actions.csv"))) {
            log.info("Retrieve actions");
            List<String> headers = getHeaders(actions);
            writer.append(headers.stream().collect(Collectors.joining(SEPARATOR, "", "\n")));
            export(writer, actions, headers);
            log.info("Export done, {} actions", actions.size());
        }

        log.info("End");
    }

    protected Stream<Path> getFiles() {
        try {
            return Files.list(Paths.get("./har/"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected ArrayNode extractInfo(Path file) {
        log.info("Processing {}", file);
        JsonNode har = readFile(file);
        ArrayNode pages = extractPages(har);
        ArrayNode entries = extractEntries(har);
        enrichEntries(pages, entries, file);
        log.info("Processing {} done, {} entries", file, entries.size());
        return entries;
    }

    protected JsonNode readFile(Path file) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected ArrayNode identifyActions(ArrayNode entries, ArrayNode actionRules) {
        ArrayNode actionInstances = newArray();
        for (int i = 0; i < actionRules.size(); i++) {
            actionInstances.addAll(identifyAction(entries, actionRules.get(i)));
        }
        return actionInstances;
    }

    protected ArrayNode identifyAction(ArrayNode entries, JsonNode actionRule) {
        ArrayNode actionInstances = newArray();
        for (int i = 0; i < entries.size(); i++) {
            JsonNode startingEntry = entries.get(i);
            if (matchRule(startingEntry, actionRule.get("firstRequest"))) {
                for (int j = i; j < entries.size(); j++) {
                    JsonNode endingEntry = entries.get(j);
                    if (matchRule(endingEntry, actionRule.get("lastRequest"))) {
                        long startTime = startingEntry.get("startMs_rel").asLong();
                        long endTime = endingEntry.get("endMs_rel").asLong();
                        long diff = endTime - startTime;
                        log.info("Found action {}, duration {}ms", actionRule.get("name").asText(), diff);
                        ObjectNode actionInstance = newObject();
                        actionInstance.set("name", actionRule.get("name"));
                        actionInstance.set("duration", new LongNode(diff));
                        actionInstance.set("pageref", startingEntry.get("pageref"));
                        actionInstance.set("startedDateTime", startingEntry.get("startedDateTime"));
                        actionInstance.set("calls", new IntNode(j-i));
                        actionInstances.add(actionInstance);
                        break;
                    }
                }
                break;
            }
        }
        return actionInstances;
    }

    protected boolean matchRule(JsonNode entry, JsonNode rule) {
        Iterator<String> fields = rule.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!entry.has(field)) {
                return false;
            }
            String entryValue = entry.get(field).asText();
            String ruleRegex = rule.get(field).asText();
            if (!Pattern.matches(ruleRegex, entryValue)) {
                return false;
            }
        }
        return true;
    }

    protected void enrichEntries(ArrayNode pages, ArrayNode entries, Path file) {
        entries.forEach(entry -> {
            String pageRef = entry.get("pageref").textValue();
            JsonNode ref = findByPageRef(pages, pageRef);
            ObjectNode obj = (ObjectNode) entry;
            if (ref != null) {
                obj.set("pageStartedDateTime", ref.get("startedDateTime"));
                obj.set("pageStartMs", new LongNode(toMillis(ref.get("startedDateTime").asText())));
                obj.set("pageTitle", ref.get("title"));
            }
            obj.set("file", new TextNode(file.getFileName().toString()));
        });
    }

    protected JsonNode findByPageRef(ArrayNode nodes, String pageRef) {
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            String currPageRef = node.get("id").textValue();
            if (pageRef.equals(currPageRef)) {
                return node;
            }
        }
        return null;
    }

    protected ArrayNode extractPages(JsonNode har) {
        ArrayNode array = newArray();
        JsonNode pages = har.get("log").get("pages");
        ((ArrayNode) pages).forEach(page -> {
            ObjectNode obj = newObject();
            obj.set("startedDateTime", page.get("startedDateTime"));
            obj.set("startMs", new LongNode(toMillis(obj.get("startedDateTime").asText())));
            obj.set("id", page.get("id"));
            obj.set("title", page.get("title"));
            array.add(obj);
        });
        return array;
    }

    protected ArrayNode extractEntries(JsonNode har) {
        ArrayNode array = newArray();
        JsonNode entries = har.get("log").get("entries");
        ((ArrayNode) entries).forEach(entry -> {
            ObjectNode obj = newObject();

            obj.set("pageref", entry.get("pageref"));
            obj.set("startedDateTime", entry.get("startedDateTime"));
            obj.set("time", entry.get("time"));

            JsonNode request = entry.get("request");
            obj.set("method", request.get("method"));
            obj.set("url", request.get("url"));

            JsonNode response = entry.get("response");
            obj.set("status", response.get("status"));

            JsonNode timings = entry.get("timings");
            Iterator<Map.Entry<String, JsonNode>> fields = timings.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                obj.set(field.getKey(), field.getValue());
            }

            obj.set("startMs", new LongNode(toMillis(obj.get("startedDateTime").asText())));
            obj.set("endMs", new LongNode(obj.get("time").asLong() + toMillis(obj.get("startedDateTime").asText())));
            array.add(obj);
        });
        return array;
    }

    protected ArrayNode newArray() {
        return (new ObjectMapper()).createArrayNode();
    }

    protected ObjectNode newObject() {
        return (new ObjectMapper()).createObjectNode();
    }

    protected long toMillis(String str) {
        LocalDateTime date = toDate(str);
        return date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    protected LocalDateTime toDate(String str) {
        DateTimeFormatter pattern = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        return LocalDateTime.parse(str, pattern);
    }

    protected void export(BufferedWriter writer, ArrayNode array, List<String> headers) throws IOException {
        array.forEach(element -> {
            headers.forEach(header -> {
                String text = "";
                if (element.has(header)) {
                    text = element.get(header).asText();
                }
                write(writer, text);
                write(writer, SEPARATOR);
            });
            write(writer, "\n");
        });
    }

    protected List<String> getHeaders(ArrayNode array) {
        List<String> headers = new ArrayList<>();
        array.forEach(element -> {
            Iterator<String> it = element.fieldNames();
            while (it.hasNext()) {
                String header = it.next();
                if (!headers.contains(header)) {
                    headers.add(header);
                }
            }
        });
        return headers;
    }

    protected void write(BufferedWriter writer, String text) {
        try {
            writer.append(text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void applyRelativeTime(ArrayNode array) {
        long min = -1;
        for (int i = 0; i < array.size(); i++) {
            long tmp = array.get(i).get("startMs").asLong();
            if (tmp < min || min == -1) {
                min = tmp;
            }
            tmp = array.get(i).get("endMs").asLong();
            if (tmp < min) {
                min = tmp;
            }
        }
        for (int i = 0; i < array.size(); i++) {
            long start = array.get(i).get("startMs").asLong();
            long end = array.get(i).get("endMs").asLong();
            ((ObjectNode) array.get(i)).set("startMs_rel", new LongNode(start - min));
            ((ObjectNode) array.get(i)).set("endMs_rel", new LongNode(end - min));
        }
    }
}
