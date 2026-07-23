package ru.tutor.codeclass.web;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.tutor.codeclass.domain.User;
import ru.tutor.codeclass.service.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/boards")
public class WhiteboardApiController {
    private final AccountService accounts;
    private final WhiteboardService boards;
    private final BoardRealtimeHub hub;
    private final ObjectMapper mapper;

    public WhiteboardApiController(AccountService accounts, WhiteboardService boards,
                                   BoardRealtimeHub hub, ObjectMapper mapper) {
        this.accounts = accounts; this.boards = boards; this.hub = hub; this.mapper = mapper;
    }

    @GetMapping("/{publicId}/snapshot")
    WhiteboardService.Snapshot snapshot(Authentication auth, @PathVariable UUID publicId) {
        return boards.snapshot(current(auth), publicId);
    }

    @PostMapping("/{publicId}/images")
    WhiteboardService.MutationResult upload(Authentication auth, @PathVariable UUID publicId,
                                            @RequestParam MultipartFile file,
                                            @RequestParam(defaultValue = "0") double left,
                                            @RequestParam(defaultValue = "0") double top) {
        var result = boards.uploadImage(current(auth), publicId, file, left, top);
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "object.created");
        event.put("revision", result.revision());
        event.set("object", mapper.valueToTree(result.object()));
        hub.broadcast(publicId, event, null);
        return result;
    }

    @GetMapping("/{publicId}/images/{objectId}")
    ResponseEntity<Resource> image(Authentication auth, @PathVariable UUID publicId, @PathVariable UUID objectId) {
        var download = boards.loadImage(current(auth), publicId, objectId);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(download.image().getOriginalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.image().getContentType()))
                .contentLength(download.image().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(download.resource());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }
}
