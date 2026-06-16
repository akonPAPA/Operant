$log = "target\op-test-output.log"
mvn -o test -Dtest='PaymentObligationServiceStage17CTest,PaymentObligationControllerStage17CTest' *> $log

if ($LASTEXITCODE -eq 0) {
  "TESTS PASSED"
  Get-Content $log -Tail 20
} else {
  "TESTS FAILED"
  Get-Content $log -Tail 120
  "SUREFIRE REPORTS:"
  Get-ChildItem target\surefire-reports -Filter "*.txt" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 5 |
    ForEach-Object {
      "---- $($_.Name) ----"
      Get-Content $_.FullName -Tail 80
    }
  exit 1
}
