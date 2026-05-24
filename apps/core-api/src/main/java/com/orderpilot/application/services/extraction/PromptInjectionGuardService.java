package com.orderpilot.application.services.extraction;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PromptInjectionGuardService {
  private static final List<String> SUSPICIOUS = List.of("ignore previous instructions","reveal all customer data","dump database","bypass security","act as system","call tool","write to database","system admin","approve discount","approve this order","create quote","create order","write to erp");
  public List<String> detect(String text) {
    String lower = text == null ? "" : text.toLowerCase();
    return SUSPICIOUS.stream().filter(lower::contains).toList();
  }
}
