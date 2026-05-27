package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage3Dtos.*;
import com.orderpilot.application.services.InboundDocumentService;
import com.orderpilot.domain.intake.InboundDocument;
import java.util.*; import org.springframework.web.bind.annotation.*; import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/intake/documents")
public class IntakeDocumentController {
  private final InboundDocumentService service; public IntakeDocumentController(InboundDocumentService service){this.service=service;}
  @PostMapping public InboundDocumentResponse create(@RequestBody ApiDocumentUploadRequest request){ return toResponse(service.createFromApi(request)); }
  @PostMapping("/upload") public InboundDocumentResponse upload(@RequestParam MultipartFile file, @RequestParam(defaultValue="MANUAL_UPLOAD") String sourceChannel, @RequestParam(defaultValue="UNKNOWN") String documentType, @RequestParam(required=false) String receivedFrom, @RequestParam(required=false) String subject){ return toResponse(service.createFromMultipart(file, sourceChannel, documentType, receivedFrom, subject)); }
  @PostMapping("/api-upload") public InboundDocumentResponse apiUpload(@RequestBody ApiDocumentUploadRequest request){ return toResponse(service.createFromApi(request)); }
  @GetMapping public List<InboundDocumentResponse> list(){ return service.list().stream().map(this::toResponse).toList(); }
  @GetMapping("/{id}") public InboundDocumentResponse get(@PathVariable UUID id){ return toResponse(service.get(id)); }
  private InboundDocumentResponse toResponse(InboundDocument d){ return new InboundDocumentResponse(d.getId(), d.getSourceChannel(), d.getDocumentType(), d.getStatus(), d.getOriginalFilename(), d.getContentType(), d.getFileSizeBytes(), d.getSha256Fingerprint(), d.getReceivedAt()); }
}
