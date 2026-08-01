package io.github.mikuwwl.matchingreplay.api;

import io.github.mikuwwl.matchingreplay.application.ReplayJobSnapshot;
import io.github.mikuwwl.matchingreplay.application.ReplayJobs;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/replays")
public class ReplayController
{
    private final ReplayJobs replayJobs;

    public ReplayController(final ReplayJobs replayJobs)
    {
        this.replayJobs = replayJobs;
    }

    @PostMapping
    public ResponseEntity<ReplayJobResponse> start(
        @Valid @RequestBody final StartReplayRequest request)
    {
        final ReplayJobSnapshot snapshot = replayJobs.start(request.toCommand());
        final URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{jobId}")
            .buildAndExpand(snapshot.jobId())
            .toUri();
        return ResponseEntity.accepted()
            .location(location)
            .body(ReplayJobResponse.from(snapshot));
    }

    @GetMapping("/{jobId}")
    public ReplayJobResponse get(@PathVariable final UUID jobId)
    {
        return replayJobs.find(jobId)
            .map(ReplayJobResponse::from)
            .orElseThrow(() -> new ReplayNotFoundException(jobId));
    }

    @GetMapping
    public List<ReplayJobResponse> list()
    {
        return replayJobs.list().stream()
            .map(ReplayJobResponse::from)
            .toList();
    }
}
