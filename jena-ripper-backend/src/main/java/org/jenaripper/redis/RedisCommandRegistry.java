package org.jenaripper.redis;

import org.jenaripper.dto.RedisConsoleMetadataDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RedisCommandRegistry {
    private static final Set<String> FORBIDDEN = Set.of(
            "KEYS", "DEL", "UNLINK", "FLUSHDB", "FLUSHALL", "SET", "MSET", "SETEX", "PSETEX",
            "SADD", "SREM", "HSET", "HDEL", "EXPIRE", "PEXPIRE", "RENAME", "RENAMENX", "MOVE",
            "LPUSH", "RPUSH", "LPOP", "RPOP", "ZADD", "ZREM", "INCR", "DECR", "EVAL", "EVALSHA");

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public RedisCommandRegistry() {
        register("SMEMBERS", "SET", "SMEMBERS key", "Возвращает все элементы SET.", 1, 1,
                (e, a) -> result("COLLECTION", new ArrayList<>(e.smembers(a.get(0)))));
        register("SISMEMBER", "SET", "SISMEMBER key member", "Проверяет наличие элемента в SET.", 2, 2,
                (e, a) -> result("BOOLEAN", e.sismember(a.get(0), a.get(1))));
        register("SCARD", "SET", "SCARD key", "Возвращает размер SET.", 1, 1,
                (e, a) -> result("INTEGER", e.scard(a.get(0))));
        register("GET", "KEY", "GET key", "Читает string value.", 1, 1,
                (e, a) -> result("STRING", e.get(a.get(0))));
        register("TYPE", "KEY", "TYPE key", "Показывает тип Redis key.", 1, 1,
                (e, a) -> result("STRING", e.type(a.get(0))));
        register("EXISTS", "KEY", "EXISTS key", "Проверяет существование key.", 1, 1,
                (e, a) -> result("BOOLEAN", e.exists(a.get(0))));
        register("TTL", "KEY", "TTL key", "Возвращает TTL в секундах.", 1, 1,
                (e, a) -> result("INTEGER", e.ttl(a.get(0))));
        register("PTTL", "KEY", "PTTL key", "Возвращает TTL в миллисекундах.", 1, 1,
                (e, a) -> result("INTEGER", e.pttl(a.get(0))));
        register("HGET", "HASH", "HGET key field", "Читает поле HASH.", 2, 2,
                (e, a) -> result("STRING", e.hget(a.get(0), a.get(1))));
        register("HGETALL", "HASH", "HGETALL key", "Возвращает все поля HASH.", 1, 1,
                (e, a) -> result("MAP", e.hgetall(a.get(0))));
        register("HMGET", "HASH", "HMGET key field [field ...]", "Читает несколько полей HASH.", 2, 100,
                (e, a) -> result("MAP", e.hmget(a.get(0), a.subList(1, a.size()))));
        register("HLEN", "HASH", "HLEN key", "Возвращает число полей HASH.", 1, 1,
                (e, a) -> result("INTEGER", e.hlen(a.get(0))));
        register("LRANGE", "LIST", "LRANGE key start stop", "Читает диапазон LIST.", 3, 3,
                (e, a) -> result("COLLECTION", e.lrange(a.get(0), number(a.get(1)), number(a.get(2)))));
        register("LLEN", "LIST", "LLEN key", "Возвращает длину LIST.", 1, 1,
                (e, a) -> result("INTEGER", e.llen(a.get(0))));
        register("ZRANGE", "SORTED SET", "ZRANGE key start stop", "Читает диапазон SORTED SET.", 3, 3,
                (e, a) -> result("COLLECTION", e.zrange(a.get(0), number(a.get(1)), number(a.get(2)))));
        register("ZCARD", "SORTED SET", "ZCARD key", "Возвращает размер SORTED SET.", 1, 1,
                (e, a) -> result("INTEGER", e.zcard(a.get(0))));
        register("ZSCORE", "SORTED SET", "ZSCORE key member", "Возвращает score элемента.", 2, 2,
                (e, a) -> result("STRING", e.zscore(a.get(0), a.get(1))));
        register("SCAN", "KEY", "SCAN cursor [MATCH pattern] [COUNT count]", "Безопасно обходит keys по курсору.", 1, 5, this::scan);
    }

    public Dispatch dispatch(String rawCommand, List<String> rawArgs, RedisCommandExecutor executor) {
        String command = rawCommand == null ? "" : rawCommand.trim().toUpperCase(Locale.ROOT);
        if (FORBIDDEN.contains(command)) {
            String message = "KEYS".equals(command)
                    ? "Команда KEYS отключена. Используйте SCAN с MATCH."
                    : "Команда " + command + " недоступна в READ ONLY режиме.";
            throw new RedisCommandException("REDIS_COMMAND_FORBIDDEN", message);
        }
        Entry entry = entries.get(command);
        if (entry == null) throw new RedisCommandException("REDIS_COMMAND_INVALID", "Неизвестная или недоступная Redis-комана: " + command + ".");
        List<String> args = rawArgs == null ? List.of() : rawArgs.stream().map(value -> value == null ? "" : value).toList();
        if (args.size() < entry.minArgs || args.size() > entry.maxArgs) {
            throw new RedisCommandException("REDIS_COMMAND_INVALID", "Синтаксис: " + entry.metadata.syntax());
        }
        Result result = entry.handler.execute(executor, args);
        return new Dispatch(entry.metadata, result);
    }

    public List<RedisConsoleMetadataDto.Command> metadata() {
        return entries.values().stream().map(Entry::metadata).toList();
    }

    private Result scan(RedisCommandExecutor executor, List<String> args) {
        String cursor = args.get(0);
        if (!cursor.matches("\\d+")) throw invalidScan();
        String pattern = null;
        long count = 100;
        for (int index = 1; index < args.size(); index += 2) {
            if (index + 1 >= args.size()) throw invalidScan();
            String option = args.get(index).toUpperCase(Locale.ROOT);
            if ("MATCH".equals(option)) pattern = args.get(index + 1);
            else if ("COUNT".equals(option)) count = number(args.get(index + 1));
            else throw invalidScan();
        }
        if (count < 1 || count > 1000) throw new RedisCommandException("REDIS_COMMAND_INVALID", "COUNT должен быть от 1 до 1000.");
        RedisCommandExecutor.ScanResult scan = executor.scan(cursor, pattern, count);
        return new Result("SCAN", scan.keys(), scan.cursor(), scan.keys().size());
    }

    private RedisCommandException invalidScan() {
        return new RedisCommandException("REDIS_COMMAND_INVALID", "Синтаксис: SCAN cursor [MATCH pattern] [COUNT count]");
    }

    private static long number(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw new RedisCommandException("REDIS_COMMAND_INVALID", "Ожидалось целое число: " + value + "."); }
    }

    private static Result result(String type, Object value) {
        int count = value instanceof java.util.Collection<?> collection ? collection.size()
                : value instanceof Map<?, ?> map ? map.size() : 1;
        return new Result(type, value, null, value == null ? 0 : count);
    }

    private void register(String name, String category, String syntax, String description, int minArgs, int maxArgs, Handler handler) {
        entries.put(name, new Entry(new RedisConsoleMetadataDto.Command(name, category, "READ", syntax, description), minArgs, maxArgs, handler));
    }

    public record Dispatch(RedisConsoleMetadataDto.Command metadata, Result result) {}
    public record Result(String type, Object value, String cursor, int count) {
        public Result(String type, Object value) { this(type, value, null, 0); }
    }
    private record Entry(RedisConsoleMetadataDto.Command metadata, int minArgs, int maxArgs, Handler handler) {}
    @FunctionalInterface private interface Handler { Result execute(RedisCommandExecutor executor, List<String> args); }
}
