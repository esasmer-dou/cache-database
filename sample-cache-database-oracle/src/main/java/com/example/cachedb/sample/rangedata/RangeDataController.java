package com.example.cachedb.sample.rangedata;

import com.example.cachedb.sample.controls.ControlsAccess;
import com.reactor.cachedb.core.repository.*;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("demo")
@ConditionalOnProperty(name = "sample.range-data.enabled", havingValue = "true")
@RequestMapping("/api/examples/v019/range-data")
public final class RangeDataController {
    private static final Sort ORDER = Sort.by(Sort.Order.asc("rankValue"), Sort.Order.asc("rowId"));
    private static final String CURSOR = "$cachedb:cursor";
    private final RangeRoutes routes;
    private final RangeDataRepository data;
    private final RangeDataGuard guard;

    public RangeDataController(RangeRoutes routes, RangeDataRepository data, ControlsAccess access) {
        this.routes = routes; this.data = data; this.guard = new RangeDataGuard(access);
    }
    public record Input(String rank, String status) {}
    public record Row(String id, String tenant, String run, String slot, String rank, String status) {
        static Row from(RangeDataRow row) {
            String id = row.rowId();
            return new Row(id, row.tenantId(), row.runId(), id.substring(id.lastIndexOf(':') + 1),
                    Long.toString(row.rankValue()), row.status());
        }
    }
    public record Saved(Row row, boolean sqlDurabilityConfirmed) {}
    public record NativeWindow(List<Row> items, String nextCursor, boolean sourceCoverageConfirmed) {}
    public record DataPage(List<Row> items, int number, int size, long total, boolean hasNext) {}
    public record DataSlice(List<Row> items, int number, int size, boolean hasNext) {}
    public record DataWindow(List<Row> items, String nextCursor, boolean hasNext) {}
    public record Stats(long count, boolean exists) {}

    @PutMapping("/{run}/rows/{slot}")
    public ResponseEntity<Saved> save(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String run, @PathVariable String slot, @RequestBody Input input) {
        return guard.call(auth, causal, tenant -> {
            if (input == null) throw new IllegalArgumentException("JSON body required");
            var row = new RangeDataRow(RangeDataGuard.id(tenant, run, slot), tenant,
                    RangeDataGuard.run(run), input.status(), RangeDataGuard.rank(input.rank()));
            return ResponseEntity.accepted().body(new Saved(Row.from(data.save(row)), false));
        });
    }

    @GetMapping("/{run}/rows/{slot}")
    public Row read(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String run, @PathVariable String slot) {
        return guard.call(auth, causal, tenant -> Row.from(data.findById(RangeDataGuard.id(tenant, run, slot))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No resident row"))));
    }

    @DeleteMapping("/{run}/rows/{slot}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String run, @PathVariable String slot) {
        return guard.call(auth, causal, tenant -> {
            data.deleteById(RangeDataGuard.id(tenant, run, slot));
            return ResponseEntity.noContent().build();
        });
    }

    @GetMapping("/{run}/native")
    public NativeWindow nativeRange(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String run,
            @RequestParam(defaultValue = "-9223372036854775808") String minimum,
            @RequestParam(defaultValue = "9223372036854775807") String maximum,
            @RequestParam(defaultValue = "2") int limit,
            @RequestParam(defaultValue = "false") boolean descending,
            @RequestParam(required = false) String cursor) {
        return guard.call(auth, causal, tenant -> {
            String scope = RangeDataGuard.run(run);
            long min = RangeDataGuard.rank(minimum), max = RangeDataGuard.rank(maximum);
            if (min > max) throw new IllegalArgumentException("minimum exceeds maximum");
            var window = WindowRequest.of(RangeDataGuard.limit(limit), RangeDataGuard.cursor(cursor));
            var result = descending ? routes.descending(tenant, scope, min, max, window)
                    : routes.ascending(tenant, scope, min, max, window);
            return nativeResult(result);
        });
    }

    @GetMapping("/{run}/native/seek")
    public NativeWindow nativeSeek(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String run, @RequestParam String afterRank, @RequestParam String afterSlot,
            @RequestParam(defaultValue = "-9223372036854775808") String minimum,
            @RequestParam(defaultValue = "9223372036854775807") String maximum,
            @RequestParam(defaultValue = "2") int limit) {
        return guard.call(auth, causal, tenant -> {
            long min = RangeDataGuard.rank(minimum), max = RangeDataGuard.rank(maximum);
            if (min > max) throw new IllegalArgumentException("minimum exceeds maximum");
            return nativeResult(routes.seek(tenant, RangeDataGuard.run(run), min, max,
                    WindowRequest.first(RangeDataGuard.limit(limit)), RangeDataGuard.rank(afterRank),
                    RangeDataGuard.id(tenant, run, afterSlot)));
        });
    }

    @GetMapping("/{run}/data/page")
    public DataPage page(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String run, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "2") int limit) {
        return guard.call(auth, causal, tenant -> {
            var result = data.findByRunIdAndStatus(RangeDataGuard.run(run), "OPEN", pageable(page, limit));
            return new DataPage(rows(result.getContent()), result.getNumber(), result.getSize(),
                    result.getTotalElements(), result.hasNext());
        });
    }

    @GetMapping("/{run}/data/slice")
    public DataSlice slice(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String run, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "2") int limit) {
        return guard.call(auth, causal, tenant -> {
            var result = data.readByRunIdAndStatus(RangeDataGuard.run(run), "OPEN", pageable(page, limit));
            return new DataSlice(rows(result.getContent()), result.getNumber(), result.getSize(), result.hasNext());
        });
    }

    @GetMapping("/{run}/data/window")
    public DataWindow window(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String run, @RequestParam(defaultValue = "2") int limit,
            @RequestParam(required = false) String cursor) {
        return guard.call(auth, causal, tenant -> {
            String token = RangeDataGuard.cursor(cursor);
            ScrollPosition position = token == null ? ScrollPosition.keyset()
                    : ScrollPosition.of(Map.of(CURSOR, token), ScrollPosition.Direction.FORWARD);
            var result = data.readByRunIdAndStatusOrderByRankValueAscRowIdAsc(
                    RangeDataGuard.run(run), "OPEN", position, Limit.of(RangeDataGuard.limit(limit)));
            String next = null;
            if (result.hasNext() && !result.isEmpty()) {
                var returned = (KeysetScrollPosition) result.positionAt(result.size() - 1);
                next = (String) returned.getKeys().get(CURSOR);
                if (next == null) throw new IllegalStateException("Adapter returned no continuation");
            }
            return new DataWindow(rows(result.getContent()), next, result.hasNext());
        });
    }

    @GetMapping("/{run}/data/stats")
    public Stats stats(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal, @PathVariable String run) {
        return guard.call(auth, causal, tenant -> {
            String scope = RangeDataGuard.run(run);
            return new Stats(data.countByRunIdAndStatus(scope, "OPEN"), data.existsByRunIdAndStatus(scope, "OPEN"));
        });
    }

    private static Pageable pageable(int page, int limit) {
        int size = RangeDataGuard.limit(limit);
        if (page < 0 || (long) page * size > 128)
            throw new IllegalArgumentException("Offset must be 0..128; use keyset Window for continuation");
        return PageRequest.of(page, size, ORDER);
    }
    private static List<Row> rows(List<RangeDataRow> rows) { return rows.stream().map(Row::from).toList(); }
    private static NativeWindow nativeResult(HotWindow<RangeDataRow> window) {
        return new NativeWindow(rows(window.items()), window.nextCursor(), window.complete());
    }
}
