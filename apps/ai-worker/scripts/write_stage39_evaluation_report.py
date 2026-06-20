r"""Write the safe Stage 39 advisory extraction evaluation report.

Run from apps/ai-worker:
    .\.venv\Scripts\python.exe scripts\write_stage39_evaluation_report.py .\stage39-report.json
"""

from pathlib import Path
import sys

from orderpilot_ai_worker.evaluation import run_default_evaluation, write_evaluation_report


def main() -> int:
    output = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("stage39-evaluation-report.json")
    summary = run_default_evaluation()
    report_path = write_evaluation_report(summary, output)
    status = "PASS" if summary.all_passed else "FAIL"
    print(
        f"{status} wrote {report_path} "
        f"cases={summary.total_cases} failed={summary.failed_cases} "
        f"unsafe_partial_data_violations={summary.unsafe_partial_data_violations}"
    )
    return 0 if summary.all_passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
