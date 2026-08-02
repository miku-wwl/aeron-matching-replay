package io.github.mikuwwl.matchingreplay.api;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayProgress;
import io.github.mikuwwl.matchingreplay.application.ReplayJobSnapshot;
import io.github.mikuwwl.matchingreplay.application.ReplayJobState;
import io.github.mikuwwl.matchingreplay.application.ReplayJobs;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayControllerTest
{
    private static final UUID JOB_ID =
        UUID.fromString("844aa1ef-8c6f-4b49-b5f3-99450dc39a53");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp()
    {
        final ReplayCommand command =
            new ReplayCommand(42, "orders", 1024L, 300L, 123L, "incident-9");
        final ReplayJobSnapshot snapshot = new ReplayJobSnapshot(
            JOB_ID,
            command,
            ReplayJobState.RUNNING,
            Instant.parse("2026-08-02T00:00:00Z"),
            Instant.parse("2026-08-02T00:00:01Z"),
            null,
            ReplayProgress.snapshot(
                128,
                576,
                1024,
                200,
                100,
                2,
                512,
                10_000,
                Instant.parse("2026-08-02T00:00:02Z")),
            null,
            null);
        mockMvc = mockMvcFor(snapshot);
    }

    @Test
    void acceptsExplicitRecordingAndReturnsStatusLocation() throws Exception
    {
        mockMvc.perform(post("/api/v1/replays")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "recordingId": 42,
                      "checkpointKey": "orders",
                      "stopPosition": 1024,
                      "expectedLastEventSequence": 300,
                      "expectedReplayDigest": "123",
                      "correlationId": "incident-9"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(header().string(
                "Location",
                "http://localhost/api/v1/replays/" + JOB_ID))
            .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
            .andExpect(jsonPath("$.state").value("RUNNING"))
            .andExpect(jsonPath("$.command.recordingId").value(42))
            .andExpect(jsonPath("$.command.expectedReplayDigest").value("123"))
            .andExpect(jsonPath("$.progress.currentPosition").value(576))
            .andExpect(jsonPath("$.progress.progressPercent").value(50.0))
            .andExpect(jsonPath("$.progress.lastCheckpointPosition").value(512));
    }

    @Test
    void reportsMissingJobAsProblemDetail() throws Exception
    {
        mockMvc.perform(get("/api/v1/replays/{jobId}", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Not Found"));
    }

    @Test
    void requiresRecordingIdToBePresent() throws Exception
    {
        mockMvc.perform(post("/api/v1/replays")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "checkpointKey": "orders",
                      "expectedLastEventSequence": 300,
                      "expectedReplayDigest": "123"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void requiresExpectedSequenceAndReplayDigest() throws Exception
    {
        mockMvc.perform(post("/api/v1/replays")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "recordingId": 42,
                      "checkpointKey": "orders"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void exposesStructuredReplayFailure() throws Exception
    {
        final ReplayCommand command =
            new ReplayCommand(42, "orders", 1024L, 300L, 123L, "incident-9");
        final ReplayFailure failure = ReplayFailure.sequenceGap(512, 200, 202)
            .withReplayContext(42, 512, 1024, 200);
        final ReplayJobSnapshot snapshot = new ReplayJobSnapshot(
            JOB_ID,
            command,
            ReplayJobState.FAILED,
            Instant.parse("2026-08-02T00:00:00Z"),
            Instant.parse("2026-08-02T00:00:01Z"),
            Instant.parse("2026-08-02T00:00:02Z"),
            null,
            null,
            failure);

        mockMvcFor(snapshot).perform(get("/api/v1/replays/{jobId}", JOB_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("FAILED"))
            .andExpect(jsonPath("$.failure.code").value(
                ReplayFailureCode.SEQUENCE_GAP.name()))
            .andExpect(jsonPath("$.failure.currentPosition").value(512))
            .andExpect(jsonPath("$.failure.lastAppliedEventSequence").value(200))
            .andExpect(jsonPath("$.failure.receivedEventSequence").value(202));
    }

    private static MockMvc mockMvcFor(final ReplayJobSnapshot snapshot)
    {
        final ReplayJobs jobs = new ReplayJobs()
        {
            @Override
            public ReplayJobSnapshot start(final ReplayCommand ignored)
            {
                return snapshot;
            }

            @Override
            public Optional<ReplayJobSnapshot> find(final UUID jobId)
            {
                return JOB_ID.equals(jobId) ? Optional.of(snapshot) : Optional.empty();
            }

            @Override
            public List<ReplayJobSnapshot> list()
            {
                return List.of(snapshot);
            }
        };
        return MockMvcBuilders
            .standaloneSetup(new ReplayController(jobs))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }
}
