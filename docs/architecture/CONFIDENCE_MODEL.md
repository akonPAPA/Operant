# Confidence Model

Stage 4 confidence is intentionally simple:

- text quality contributes to overall confidence;
- provider/rule match confidence contributes to overall confidence;
- schema validity contributes a small positive signal;
- prompt injection risk reduces confidence.

Field and line item confidence comes from provider/rule confidence and is stored with each extracted value.

Confidence is not approval. It only helps operators and Stage 5 validation decide what requires review.