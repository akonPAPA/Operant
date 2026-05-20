from orderpilot_ai_worker.tasks.process_inbound_document import process_inbound_document


def main() -> None:
    result = process_inbound_document(document_id="demo-document", text="Need 20 brake pads for Camry 2018")
    print(result.model_dump_json())


if __name__ == "__main__":
    main()