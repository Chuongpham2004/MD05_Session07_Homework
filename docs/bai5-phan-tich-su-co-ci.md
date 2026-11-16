# Bài 5 — Phân tích sự cố CI: `UnsupportedClassVersionError`

**Service:** `notification-service` (QuickBite)
**Vai trò:** DevOps / Lead
**Hiện tượng:** Pipeline CI thất bại tại bước build với thông báo:

```
UnsupportedClassVersionError: class file has been compiled by a more recent version
of the Java Runtime (class file version 65.0), this version of the Java Runtime only
recognizes class file versions up to 61.0
```

---

## 1. Phân tích nguyên nhân

### 1.1. "Class file version" là gì và nói lên điều gì?

Mỗi file `.class` do trình biên dịch `javac` sinh ra đều có một trường **major version** ở phần header (ngay sau "magic number" `0xCAFEBABE`). Con số này cho biết file được biên dịch nhắm tới phiên bản Java nào. Một JVM chỉ chạy được các file `.class` có major version **nhỏ hơn hoặc bằng** phiên bản nó hỗ trợ. Nếu gặp file mới hơn, JVM từ chối nạp và ném `UnsupportedClassVersionError`.

Bảng ánh xạ (trích theo *The Java Virtual Machine Specification*, mục §4.1, bảng *Table 4.1-A — class file format major versions*):

| Phiên bản Java (JDK) | Class file major version |
|---|---|
| Java 8  | 52 |
| Java 11 | 55 |
| Java 17 | **61** |
| Java 21 | **65** |
| Java 25 | 69 |

> Quy luật dễ nhớ: từ Java 5 trở đi, `major version = phiên bản Java + 44`.
> Nguồn tra cứu chính thức: JVM Specification của Oracle (docs.oracle.com/javase/specs → chọn phiên bản → Chapter 4 "The class File Format", §4.1).

**Áp dụng vào thông báo lỗi:**

- `class file version 65.0` → code (hoặc một thư viện/plugin) đã được **biên dịch bằng Java 21**.
- `only recognizes class file versions up to 61.0` → JVM đang **chạy** nó là **Java 17**.

**Kết luận:** lỗi xảy ra khi bytecode được biên dịch cho Java 21 bị đưa cho JVM Java 17 thực thi. Đây là lỗi **lệch phiên bản giữa compile-time và runtime**, không phải lỗi logic trong code.

### 1.2. Lỗi này xuất hiện ở đâu trong một pipeline CI?

Trong CI, phiên bản JVM thực thi bước build được quyết định bởi `JAVA_HOME` trên runner. `JAVA_HOME` lại do bước `actions/setup-java` đặt, hoặc là JDK mặc định có sẵn của image runner nếu workflow không có bước `setup-java`. Các tình huống điển hình dẫn tới lỗi:

1. **Pipeline cài sai phiên bản JDK.** `setup-java` khai báo `java-version: '17'`, trong khi dự án (hoặc dependency/plugin của nó) yêu cầu Java 21. Gradle daemon chạy trên JDK 17 nên khi nạp một class được build cho Java 21 thì lỗi ngay ở bước build.
2. **Thiếu hẳn bước `setup-java`.** Runner dùng JDK mặc định có sẵn trong image (thường là một bản LTS cũ hơn), và phiên bản này không do dự án kiểm soát.
3. **Biên dịch một nơi, chạy một nơi.** Ví dụ compile bằng toolchain 21, nhưng một bước sau (chạy test, `java -jar`, Docker base image `eclipse-temurin:17-jre`) lại dùng runtime 17.
4. **Đưa sản phẩm build sẵn từ máy local vào repo**, ví dụ commit nhầm thư mục `build/` hoặc file `.jar` do JDK 21 tạo ra, rồi CI chạy chúng bằng JDK 17.

Với `notification-service`: lập trình viên phát triển bằng Java 21 (sinh ra bytecode 65), còn JVM trong bước build của CI là Java 17 (chỉ hiểu tới 61). Gần như chắc chắn đây là **tình huống (1) hoặc (2)**: pipeline đang cấu hình (hoặc mặc định dùng) Java 17.

### 1.3. Vì sao chạy trên máy cá nhân thì không lỗi mà lên GitHub Actions mới lỗi?

| | Máy lập trình viên | Runner GitHub Actions |
|---|---|---|
| JDK có sẵn | JDK 21 đã cài, IDE và `JAVA_HOME` đều trỏ vào 21 | Máy ảo **mới tinh mỗi lần chạy**, chỉ có JDK mặc định của image hoặc JDK do `setup-java` cài |
| Ai quyết định phiên bản | Lập trình viên (cài đặt thủ công, ngầm định) | **File `ci.yml`** (cấu hình tường minh) |
| Compile và runtime | Cùng một JDK 21 nên luôn khớp | Có thể lệch nếu `ci.yml` không khớp với dự án |

**Sự khác biệt cốt lõi:** trên máy local, compile và run cùng dùng một JDK nên không bao giờ lệch. Trên CI, môi trường được **dựng lại từ đầu** theo đúng những gì `ci.yml` mô tả. Phiên bản Java của dự án chỉ là "kiến thức ngầm" trên máy lập trình viên, không được khai báo nhất quán trong repo, nên CI dựng ra một môi trường khác (Java 17) và lỗi lộ ra. Đây là ví dụ kinh điển của vấn đề *"works on my machine"*.

---

## 2. Đề xuất giải pháp

### 2.1. Vị trí cần rà soát trong `ci.yml`

Điểm cần kiểm tra là bước **`actions/setup-java`**, cụ thể là tham số `java-version`.

**Trước (gây lỗi):** CI cài JDK 17, dự án cần 21.

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - name: Setup Java
        uses: actions/setup-java@v6
        with:
          java-version: '17'        # ❌ lệch với Java 21 của dự án
          distribution: 'temurin'

      - name: Build
        run: |
          chmod +x ./gradlew
          ./gradlew build
```

**Sau (đã sửa):** đồng bộ với Java 21.

```yaml
      - name: Setup Java
        uses: actions/setup-java@v6
        with:
          java-version: '21'        # ✅ khớp với phiên bản dùng để phát triển
          distribution: 'temurin'
          cache: 'gradle'           # (tuỳ chọn) cache dependency cho build nhanh hơn
```

**Vì sao sửa được lỗi:** `setup-java` cài JDK 21 và đặt `JAVA_HOME` trỏ vào JDK đó. Mọi bước phía sau (`./gradlew`, chạy test, `java -jar`) đều chạy trên JVM 21, hỗ trợ class file tới version 65, nên JVM nạp được bytecode và `UnsupportedClassVersionError` biến mất.

Ngoài `ci.yml`, cần rà soát thêm các chỗ khác cũng quyết định phiên bản Java:

- **`build.gradle`:** `java.toolchain.languageVersion` (hoặc `sourceCompatibility`/`targetCompatibility`) phải khớp với phiên bản trên CI.
  ```gradle
  java {
      toolchain {
          languageVersion = JavaLanguageVersion.of(21)
      }
  }
  ```
- **`Dockerfile`** (nếu có): base image runtime phải ≥ phiên bản compile, ví dụ `eclipse-temurin:21-jre`, không phải `:17-jre`.
- **`.gitignore`:** phải có `build/` và `*.jar`, để không commit nhầm bytecode build từ máy local.

### 2.2. Nguyên tắc chung để lỗi không tái diễn

1. **Một nguồn sự thật duy nhất (single source of truth) cho phiên bản Java.** Không "hardcode" phiên bản rải rác ở nhiều nơi. Khai báo phiên bản trong một file nằm trong repo, rồi để CI đọc từ đó. `actions/setup-java` hỗ trợ tham số `java-version-file`:
   ```
   # file .java-version ở thư mục gốc repo
   21
   ```
   ```yaml
   - uses: actions/setup-java@v6
     with:
       java-version-file: '.java-version'
       distribution: 'temurin'
   ```
   Khi nâng cấp Java, chỉ cần sửa một file, và CI tự động theo.

2. **Đồng bộ ba tầng: local, build tool, CI.** Phiên bản JDK của lập trình viên, toolchain trong `build.gradle` và `java-version` trong `ci.yml` phải là **cùng một số**. Gradle toolchain còn giúp Gradle tự kiểm tra và báo lỗi rõ ràng nếu JDK không khớp, thay vì lỗi mơ hồ lúc runtime.

3. **Luôn khai báo `setup-java` tường minh.** Không phụ thuộc vào JDK mặc định của runner image, vì GitHub có thể thay đổi nó theo thời gian (ví dụ khi nâng cấp `ubuntu-latest`).

4. **Runtime ≥ compile-time.** Môi trường chạy (CI, Docker image, server production) phải dùng phiên bản Java **lớn hơn hoặc bằng** phiên bản dùng để biên dịch. Nếu bắt buộc phải chạy trên JVM cũ hơn, biên dịch với cờ `--release` (Gradle: `options.release = 17`) để sinh bytecode tương thích.

5. **Không commit sản phẩm build.** `build/`, `target/`, `*.class`, `*.jar` phải nằm trong `.gitignore`. CI luôn phải build lại từ mã nguồn.

6. **Đưa thay đổi phiên bản Java qua review.** Mọi thay đổi `.java-version`, toolchain, `ci.yml` hoặc `Dockerfile` liên quan tới JDK nên được review cùng nhau trong một Pull Request, để CI xác nhận trước khi merge vào `main`.

---

## 3. Tóm tắt

| | |
|---|---|
| **Hiện tượng** | `UnsupportedClassVersionError`: class file 65.0, runtime chỉ hỗ trợ tới 61.0 |
| **Giải mã** | 65 = Java 21 (compile), 61 = Java 17 (runtime) |
| **Nguyên nhân gốc rễ** | Lệch phiên bản: dự án được biên dịch bằng Java 21, nhưng `ci.yml` cấu hình (hoặc mặc định) JDK 17 cho bước build |
| **Vì sao chỉ lỗi trên CI** | Local dùng chung một JDK 21 cho compile và run. CI dựng môi trường mới theo `ci.yml` và nhận JDK 17 |
| **Cách sửa** | `actions/setup-java` → `java-version: '21'`, đồng bộ với toolchain trong `build.gradle` |
| **Phòng ngừa** | Một nguồn phiên bản duy nhất (`.java-version` + `java-version-file`), đồng bộ local/Gradle/CI, runtime ≥ compile-time, không commit sản phẩm build |
