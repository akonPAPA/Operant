# Prompt Injection Defense

Customer-controlled text is hostile input.

Stage 4 flags suspicious phrases such as:

- ignore previous instructions;
- reveal all customer data;
- dump database;
- bypass security;
- act as system;
- call tool;
- write to database;
- approve discount.

Expected behavior:

- flag suspicious text;
- store warning as `AiSuggestion`;
- continue safe extraction when possible;
- never execute the instruction;
- never reveal secrets;
- never change business data.