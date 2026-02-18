# T11 Review — Verification Scripts DoD

## verify_s1.sh
```bash
./tests/verify_s1.sh
```
Output:
```
PASS C01: baseline max latency 4.023s > resilient max latency 0.995s
PASS C02: resilient QUEUE_FULL+CIRCUIT_OPEN=11847 (> 100)

Results: PASS=2 FAIL=0
```
Exit code: 0

## verify_s4.sh
```bash
./tests/verify_s4.sh
echo $?
```
Output:
```
PASS C05: S4 baseline UNAVAILABLE=1797 (> 100)
PASS C06: S4 resilient UNAVAILABLE=21 (< 100)
PASS C07: baseline UNAVAILABLE (1797) > resilient UNAVAILABLE (21)

Results: PASS=3 FAIL=0
```
Exit code: 0

## Commit
d886a0a feat: Add S1 and S4 verification scripts
