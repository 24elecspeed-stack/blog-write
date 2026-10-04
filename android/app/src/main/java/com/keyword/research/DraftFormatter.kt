package com.keyword.research

// Gemini가 소제목과 본문 사이에 빈 줄(문단 구분)을 안정적으로 넣지 않는 경우가 스타일별로
// 확인됐다(사용자 실기기 테스트) - 한 줄바꿈만 있거나 아예 붙어서 나오면, "블로그 반자동 복사"의
// 문단 분리(RichCopyTestActivity.paragraphs(), "\n\n" 기준 split)가 소제목과 본문을 하나로
// 묶어버려 소제목-본문 구조 전체가 깨진다. AI 응답을 화면에 표시하기 전에 여기서 한 번 다시
// 정리해서, Gemini가 빈 줄을 빠뜨려도 항상 "제목 - 소제목 - 본문 - 소제목 - 본문..." 형태로
// 빈 줄 하나씩만 넣어 재구성한다.
object DraftFormatter {
    // 문장이 끝나는 표시(.?!) 뒤에서 줄을 바꾼다(사용자 지시) - 네이버 블로그 특유의
    // "문장마다 한 줄씩" 읽기 스타일을 맞추기 위함이다. 문단(=소제목 하나에 딸린 본문) 안에서만
    // 줄바꿈 하나(\n)로 문장을 나누고, 문단과 문단 사이의 빈 줄(\n\n)과는 구분한다 - 그래야
    // "사진 랜덤 배치"가 쓰는 블록 단위(소제목/문단 하나 = 블록 하나)가 문장 단위로 잘게
    // 쪼개지지 않는다.
    private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")

    // 소제목으로 보이는 줄인지 판단 - RichCopyTestActivity의 판단 기준과 동일하게 맞춘다
    // (짧고, 줄바꿈이 없고, 마침표/물음표/느낌표로 끝나지 않는 한 줄).
    fun isLikelyHeading(text: String): Boolean {
        val value = text.trim()
        return value.isNotBlank() && !value.contains('\n') && value.length <= 40 &&
            value.last() !in listOf('.', '?', '!', '。')
    }

    // 첫 줄(또는 줄바꿈이 아예 없으면 첫 문장)은 제목, 그 다음부터는 소제목처럼 보이는 줄이
    // 나올 때마다 새 블록으로 끊고, 아니면 직전 블록(본문 문단)에 이어 붙인다. 그렇게 모은
    // 문단이 그래도 너무 길면(줄바꿈 자체가 없어서 소제목 감지가 안 통한 경우) 문장 단위로
    // 한 번 더 쪼갠다. 결과는 항상 빈 줄 하나로 블록을 구분한 텍스트다.
    //
    // 중요: 이 함수는 반드시 원본 글에 딱 한 번만 적용해야 한다. 소제목이 하나도 없는 글은
    // 문장 수 기준으로 4~6문단으로 재구성하는데(아래 regroupIntoParagraphs), 이미 이 함수를
    // 거친 결과(문단 안 문장이 줄바꿈 \n으로, 문단 사이는 빈 줄로 구분된 상태)를 다시 이
    // 함수에 넣으면, 문장 줄바꿈과 문단 빈 줄의 구분이 사라지고(둘 다 lines()로 쪼개짐) 문장이
    // 전부 한 덩어리로 재합쳐졌다가 다시 재구성되는데, 이 재구성이 항상 원래와 똑같은 경계로
    // 돌아온다는 보장이 없다. 그 결과 "사진을 배치할 때 셌던 N번째 문단"과 "실제 네이버에 넣을
    // 때의 N번째 문단"이 서로 달라져 사진이 엉뚱한(주로 뒤쪽) 문단에 붙는 문제가 실사용으로
    // 확인됐다("첫 소제목이 없으면 본문이 사진과 같이 맨 뒤로 밀린다"). 그래서 RichCopyTestActivity
    // 쪽에서 문단이 이미 잡힌 글을 다시 읽을 때는 이 함수를 다시 타지 않고 stripMarkdownSymbols()
    // 결과를 빈 줄 기준으로만 나눈다 - 문단 재구성은 글을 만들 때 딱 한 번만 일어난다.
    fun normalizeParagraphSpacing(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return raw

        // 줄바꿈이 아예 하나도 없이 통째로 한 줄로 오는 극단적인 경우까지 대비해, 그럴 땐
        // "첫 줄" 대신 "첫 문장"을 제목으로 취급한다.
        val (title, restText) = if (lines.size > 1) {
            lines.first() to lines.drop(1).joinToString("\n")
        } else {
            splitFirstSentence(lines.first())
        }

        val restLines = restText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val blocks = mutableListOf<String>()
        val currentParagraph = StringBuilder()

        fun flushParagraph() {
            if (currentParagraph.isNotEmpty()) {
                blocks += currentParagraph.toString().trim()
                currentParagraph.setLength(0)
            }
        }

        restLines.forEach { line ->
            if (isLikelyHeading(line)) {
                flushParagraph()
                blocks += line
            } else {
                if (currentParagraph.isNotEmpty()) currentParagraph.append(' ')
                currentParagraph.append(line)
            }
        }
        flushParagraph()

        // 소제목이 하나도 안 잡히면(모델이 짧은 소제목 줄 없이 처음부터 끝까지 완결된 문장만
        // 죽 이어 쓴 경우 - 실기기에서 확인됨) 문단 전체가 덩어리 하나로 남는다. 진짜 소제목
        // 문구를 지어내는 건 사실 왜곡이라 피하고, 대신 문장 수를 기준으로 4~6문단으로 다시
        // 나눠서 최소한 빈 줄로 구분된 여러 문단처럼은 보이게 한다.
        val effectiveBlocks = if (blocks.none { isLikelyHeading(it) }) {
            regroupIntoParagraphs(blocks.joinToString(" "))
        } else {
            blocks
        }

        val finalBlocks = effectiveBlocks.map { block ->
            if (isLikelyHeading(block)) block else breakSentences(block)
        }

        return (listOf(title) + finalBlocks).joinToString("\n\n")
    }

    private fun regroupIntoParagraphs(text: String): List<String> {
        val sentences = text.split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.size <= 1) return listOf(text)
        val groupCount = (sentences.size / 3).coerceIn(4, 6).coerceAtMost(sentences.size)
        val perGroup = kotlin.math.ceil(sentences.size.toDouble() / groupCount).toInt().coerceAtLeast(1)
        return sentences.chunked(perGroup).map { it.joinToString(" ") }
    }

    private fun splitFirstSentence(text: String): Pair<String, String> {
        val match = SENTENCE_BOUNDARY.find(text) ?: return text to ""
        val cut = match.range.first
        return text.substring(0, cut + 1).trim() to text.substring(match.range.last + 1).trim()
    }

    // 문단 하나를 문장 단위로 잘라 줄바꿈(\n) 하나로 이어 붙인다. 한 문단이 네 문장을
    // 넘으면 네 번째 문장이 끝난 뒤 빈 줄(\n\n)을 넣어 다음 문단으로 나눈다.
    private fun breakSentences(text: String): String {
        val sentences = text.split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.size <= 1) return text
        return sentences.chunked(4).joinToString("\n\n") { chunk ->
            chunk.joinToString("\n")
        }
    }

    // 정상 출력은 바깥 코드블록만 있고 그 안은 평문이다. 모델이 지시를 어겨 본문에 기호를
    // 섞었을 때 네이버에 그대로 노출되지 않도록 여기서 방어적으로 제거한다. 문단 재구성은 하지
    // 않는다. 여러 번 적용해도 안전하다.
    fun stripMarkdownSymbols(text: String): String = unwrapCopyBlock(text)
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^[-*]\\s+"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*")) { it.groupValues[1] }
        .replace(Regex("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)")) { it.groupValues[1] }

    // 복사 버튼을 만드는 바깥 코드블록 포장만 벗긴다. 코드블록 안의 글은 평문이다.
    fun unwrapCopyBlock(text: String): String {
        val normalized = text.trim().replace("\r\n", "\n")
        val lines = normalized.lines()
        if (lines.size >= 2 && lines.first().trim().startsWith("```") && lines.last().trim() == "```") {
            return lines.drop(1).dropLast(1).joinToString("\n").trim()
        }
        return normalized.replace(Regex("(?m)^```(?:[A-Za-z0-9_-]+)?\\s*$"), "").trim()
    }

    // "글 가져오기"에서 딱 한 번만 쓴다 - 기호를 뗀 뒤 normalizeParagraphSpacing으로
    // "## 소제목"·"**소제목**"이었던 줄을 소제목 블록으로 인식시켜 문단 구조를 잡는다.
    // 주의: 이 결과를 다시 이 함수에 통과시키면 안 된다(문단이 계속 바뀔 수 있음 - 아래 참고).
    // 문단이 이미 잡힌 글(articleText에 들어있는 글)을 다시 읽을 때는 RichCopyTestActivity의
    // paragraphs()가 stripMarkdownSymbols()만 쓰고 normalizeParagraphSpacing은 건너뛴다.
    fun fromMarkdown(text: String): String = normalizeParagraphSpacing(stripMarkdownSymbols(text))
}
