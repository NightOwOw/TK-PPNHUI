# Project Context: TK-PPNHUI

## 1. Chủ đề nghiên cứu

**Parallel Methods for Extracting Top-K Probabilistic Positive-Negative High-Utility Itemsets**

Viết theo định dạng manuscript của **Springer Nature journal**.

---

## 2. Bài toán (Problem Statement)

### Input
- Uncertain transaction database D = {T₁, T₂, ..., Tₙ}
- Mỗi transaction T chứa items với (quantity, existential probability)
- Profit table: profit(i) có thể **âm hoặc dương** (Positive-Negative)
- Tham số: k (số pattern cần tìm), minProb (ngưỡng EP tối thiểu)

### Output
- Top-k itemsets X xếp theo Expected Utility (EU) giảm dần
- Thỏa: Existential Probability EP(X) ≥ minProb

### Điểm khác biệt so với baseline PTK-HUIM
| | PTK-HUIM (baseline) | TK-PPNHUI (của ta) |
|---|---|---|
| Profit của items | Chỉ positive | Cả positive lẫn **negative** |
| Items trong pattern | Positive profit | Positive + Negative profit |
| Search space | Nhỏ hơn | **Lớn hơn** (negative items mở rộng không gian) |
| Upper bound | PTWU + PUB | PTWU + PUB (vẫn valid vì chỉ dùng positive utilities) |

### Công thức chính
- **Utility**: u(X,T) = Σ profit(i) × qty(i,T) — có thể âm
- **Expected Utility**: EU(X) = Σ u(X,T) × P(X,T)
- **Existential Probability**: EP(X) = 1 − Π(1 − P(X,T))
- **PTWU**: upper bound — chỉ tính positive-profit items
- **PUB**: tighter upper bound dựa trên suffix sum positive

---

## 3. Baseline tham khảo

Folder `src/` đã bị xóa (code cũ phức tạp). Toàn bộ tham khảo thuật toán lấy từ:
- `README.md` — mô tả đầy đủ thuật toán PTK-HUIM gốc
- Các khái niệm: UPU-List, PTWU pruning, EP pruning, PUB pruning, ForkJoin parallelization

---

## 4. Cấu trúc code hiện tại

```
ppnhui/
├── Transaction.java        — data model: tid, items[], qtys[], probs[]
├── ProfitTable.java        — item → profit (âm hoặc dương)
├── DataReader.java         — đọc file database (item:qty:prob) và profit table
├── UPUList.java            — cấu trúc dữ liệu chính + buildAll() + join()
├── TopKCollector.java      — min-heap top-k, thread-safe (volatile threshold + lock)
├── AlgoSEQ.java            — [1] Sequential DFS baseline
├── AlgoFJ.java             — [2] ForkJoin work-stealing
├── AlgoTPB.java            — [3] ThreadPool + PTWU-balanced round-robin
├── AlgoPLM.java            — [4] Parallel Local Mining + merge
├── AlgoPC.java             — [5] Producer-Consumer (BlockingQueue)
└── Experiment.java         — main(), chạy tất cả → ghi file kết quả
```

### Shared data flow
```
DataReader → List<Transaction> + ProfitTable
                    ↓
           UPUList.buildAll()           [Phase 1: preprocessing]
                    ↓
           List<Integer> sortedItems    [ranked by PTWU desc]
           Map<Integer,UPUList> lists   [1-itemset UPU-Lists]
                    ↓
           Algo*.mine() → TopKCollector [Phase 2+3: mining]
                    ↓
           List<Pattern> + timing info
```

---

## 5. Năm thuật toán so sánh

### [1] SEQ — Sequential
- Single-thread DFS
- Shared threshold tăng dần → prune hiệu quả
- **Baseline** để tính speedup

### [2] FJ — ForkJoin Work-Stealing
- `ForkJoinPool` + `RecursiveAction`
- Mỗi 1-itemset prefix = 1 task; work-stealing tự cân bằng tải
- Shared `TopKCollector` → threshold chia sẻ toàn bộ threads
- **Ưu**: load balance tốt nhất, pruning mạnh do shared threshold
- **Nhược**: lock contention khi nhiều thread update collector

### [3] TPB — ThreadPool + PTWU-Balanced Partitioning
- `ExecutorService` fixed thread pool
- Chia prefixes theo round-robin sau khi sắp xếp PTWU desc
  → thread 0 nhận prefix PTWU cao nhất, thread 1 nhận cao nhì, ...
- Shared `TopKCollector`
- **Ưu**: overhead thấp nhất (không có task scheduling), domain-knowledge balancing
- **Nhược**: static — không phản ứng được runtime imbalance

### [4] PLM — Parallel Local Mining + Merge
- Mỗi thread có `TopKCollector` **riêng** → zero sync during mining
- Cuối cùng merge tất cả local collectors → global top-k
- **Ưu**: không lock, scale tốt với số thread
- **Nhược**: mỗi thread không thấy threshold của thread khác → prune kém hơn → duyệt nhiều node hơn. Nhược điểm này **rõ hơn với PPNHUI** vì search space lớn hơn (negative items)

### [5] PC — Producer-Consumer
- 1 producer thread đẩy prefix vào `BlockingQueue`
- N consumer threads lấy task và chạy DFS
- Shared `TopKCollector`
- **Ưu**: dynamic scheduling không cần work-stealing, phù hợp workload bất đối xứng
- **Nhược**: queue overhead, granularity cố định ở level 1-itemset

---

## 6. Datasets

Đặt trong `data/`, format:
- Database: `item:qty:prob item:qty:prob ...` (mỗi dòng 1 transaction)
- Profit: `item profit` (mỗi dòng 1 item, profit có thể âm)

| Dataset  | File database              | File profit              |
|----------|---------------------------|--------------------------|
| Chess    | chess_database.txt        | chess_profit.txt         |
| Mushroom | mushroom_database.txt     | mushroom_profit.txt      |
| Retail   | retail_database.txt       | retail_profit.txt        |
| Kosarak  | kosarak_database.txt      | kosarak_profit.txt       |
| Accidents| accidents_database.txt    | accidents_profit.txt     |
| PUMSB    | pumsb_database.txt        | pumsb_profit.txt         |
| TCGA     | tcga_transactions.txt     | tcga_profits.txt         |
| FAERS    | FAERS_transactions.txt    | FAERS_profits.txt        |
| WQX      | wqx_transactions.txt      | wqx_profits.txt          |

---

## 7. Experiment setup (trong Experiment.java)

### Tham số test
- **k**: {50, 100, 200}
- **minProb**: {0.1}
- **threads**: {2, 4, 8}
- **Runs per config**: 3 (lấy median)

### Output files (vào `results/`)
- `performance.csv` — timing cho mọi config
  - Columns: dataset, algorithm, k, minProb, threads, run, timeTotal_ms, timePhase1_ms, timePhase2_ms, timePhase3_ms, patternsFound
- `patterns_<dataset>_<algo>_k<k>_p<minProb>_t<threads>.txt` — top-k patterns từng run

### Cách chạy
Mở `Experiment.java` → click **Run** trên `main()` trong VS Code/IntelliJ.

---

## 8. Việc cần làm (TODO)

### Ưu tiên cao
- [ ] **Correctness check**: Xác nhận SEQ và tất cả parallel methods cho cùng dataset trả về đúng cùng tập top-k patterns (EU giống nhau)
- [ ] **Cập nhật Experiment.java**: điều chỉnh datasets, k values, minProb values theo kế hoạch experiment cho paper
- [ ] **Chạy full experiments**: chạy trên tất cả datasets, ghi kết quả

### Paper (Springer Nature)
- [ ] Viết manuscript theo template `sn-article-template/sn-article.tex`
- [ ] Sections cần viết:
  - Abstract
  - Introduction (motivation, contributions)
  - Related Work (PHUIM, PPNHUI, parallel data mining)
  - Preliminaries (definitions: EU, EP, PTWU, PUB)
  - Algorithm (3-phase, UPU-List, join, pruning)
  - Parallel Methods (5 strategies, analysis)
  - Experiments (datasets, metrics, results, discussion)
  - Conclusion
- [ ] Tables: speedup, phase breakdown, scalability với k, scalability với threads
- [ ] Key insight để nhấn mạnh trong paper:
  - PLM bị ảnh hưởng nhiều hơn bởi negative items (search space lớn hơn → không share threshold tệ hơn)
  - TPB competitive với FJ nhờ domain-knowledge partitioning
  - PC bộc lộ lợi thế trên sparse/uneven datasets

### Tùy chọn (nếu cần thêm nội dung)
- [ ] Thêm `ExpScalabilityK.java` — riêng cho experiment scalability theo k
- [ ] Thêm `ExpScalabilityThreads.java` — riêng cho scalability theo số threads
- [ ] So sánh với baseline PTK-HUIM trên positive-only datasets

---

## 9. Ghi chú kỹ thuật quan trọng

### UPU-List join
- `util = u_a + u_b` (cộng utility, có thể âm)
- `remaining = min(rem_a, rem_b)` (chỉ positive suffix — valid upper bound)
- `logProb = logP_a + logP_b` (log-space để tránh underflow)
- `ptwu_joined = min(ptwu_a, ptwu_b)` (valid upper bound)

### EP tính theo log-space
```
EP(X) = 1 - exp( Σ log(1 - P(X,T)) )
```
Dùng `Math.log1p(-p)` khi p < 0.5 để tránh cancellation error.

### Two-threshold design (inherited từ baseline)
- `threshold` được đọc lock-free (volatile) bởi mining threads
- Chỉ update (lock) khi admit pattern mới vào heap

### PPNHUI vs PHUIM — item filtering
- Items với `PTWU > 0` VÀ `EP >= minProb` được giữ lại
- Items với `PTWU = 0` bị loại (không bao giờ co-occur với positive items → không thể có trong top-k pattern)
- Negative-profit items CÓ THỂ có PTWU > 0 nếu chúng xuất hiện cùng positive items

---

## 10. Template paper

`sn-article-template/` chứa:
- `sn-article.tex` — template LaTeX chính
- `sn-jnl.cls` — Springer Nature journal class
- `sn-bibliography.bib` — bibliography template
- `sn-article.pdf` / `user-manual.pdf` — hướng dẫn
