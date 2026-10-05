package com.keyword.research

// "사진으로 글쓰기" - 크롬 확장프로그램 딸깍 SNS(ddalkkak-extracted/photo-writer/sidepanel.js의
// buildVisionBatchPrompt + buildUserArticlePromptWithAnalysis)가 실제로 쓰는 문구를 최대한 그대로
// 옮겨왔다(사용자 지시 - "명령문을 원래 딸깍 명령문으로 100프로 다 바꿔"). 원본과 똑같이 2단계로
// 나눴다(사용자 지적 - "익스텐션은 먼저 사진을 분석하고 난뒤에 분석 글을 가지고 글을 써. 우리는
// 하나만 하고 있어 - 분석 후 글쓰기를 해야 해"):
//   1단계(buildVisionAnalysisPrompt) - 사진만 보고 JSON으로 피사체·시각 디테일을 분석해 받는다.
//   2단계(build) - 1단계에서 받은 분석 결과를 "사전 분석된 사진 목록"으로 그대로 넣고 글을 쓴다
//                  (원본의 imageCatalog 비-커스텀 모드 블록과 동일한 형태).
// 원본이 지도 사진 글자만 "흐릿해도 최선을 다해 정확히" 읽으라고 한 것은, 사진 전반(라벨·계기판·
// 제품명 등)으로 넓혀서 1단계 분석 지침에 넣었다(사용자 지적 - "사진 OCR을 잘못하고 있어").
//
// 이 앱만의 추가 레이어(원본엔 없음, 전부 사용자가 별도로 요청한 것):
// - 글 내용은 메인 화면 "키워드와 현장 정보"의 키워드·사실관계를 그대로 따른다.
// - PromptBuilder.editorialRules()/informationalGuardrails()로 사실 왜곡·출장 서술을 막는다.
// - 블로그(전화번호)마다 문체·소제목 어투·콜아웃 문구를 다르게 해서 6개 블로그가 똑같은 템플릿
//   패턴으로 안 보이게 한다.
object PhotoWriterPromptBuilder {

    private const val ACCENT = "#2F6FED"
    private const val HEADING_BG = "#EAF1FE"
    private const val HEADING_TEXT = "#173B70"
    private const val HIGHLIGHT_BG = "#FFF3BF"
    private const val CALLOUT_BG = "#F0F6FF"
    private const val CALLOUT_TEXT = "#0B1F3A"

    // 콜아웃 박스 제목도 6개 블로그가 전부 "💡 핵심 포인트"로 똑같으면 디자인 서식이 아니라
    // 반복되는 문구 패턴으로 보일 수 있어(사용자 지시), 블로그 아이디별로 다른 아이콘·문구를
    // 쓴다. 블로그 수(6개 고정 + 직접 추가분)보다 목록이 짧아도 순환해서 쓴다.
    private val calloutLabels = listOf(
        "💡 핵심 포인트",
        "📌 체크포인트",
        "🔑 꼭 알아둘 점",
        "🧭 참고할 포인트",
        "📝 기억해 둘 점",
        "⚡ 눈여겨볼 포인트"
    )

    private fun calloutLabelFor(blogId: Int): String =
        calloutLabels[((blogId - 1).mod(calloutLabels.size))]

    // 1단계 - 원본 buildVisionBatchPrompt를 그대로 옮겼다(10장 단위 배치 전송은 수동 흐름에
    // 맞지 않아 뺐다 - 사용자가 한 번에 전부 첨부한다).
    fun buildVisionAnalysisPrompt(photoCount: Int): String = buildString {
        val lastIdx = (photoCount - 1).coerceAtLeast(0)
        append("당신은 세계 최고 수준의 멀티모달 비전 AI이자 전문 블로그 콘텐츠 기획자입니다.\n")
        append("이 요청문 바로 다음에 사진 ${photoCount}장을 0번부터 ${lastIdx}번까지 순서대로 첨부할 거예요. ")
        append("각 사진을 순서대로 직접 눈으로 확인하고 **사진의 시각적 특징을 정확하고 생생하게 분석**해 주세요. 분석 결과는 나중에 이 사진들로 블로그 글을 쓸 때 그대로 사용할 거예요.\n\n")
        append("[시각 분석 지침]\n")
        append("각 사진에 대해:\n")
        append("1. **피사체 파악**: 사진에 실제로 찍힌 피사체와 장면을 정확히 식별하세요.\n")
        append("2. **블로그 서사용 시각 디테일**: 글에서 독자의 공감을 이끌 수 있는 핵심 시각적 특징(상태, 색감, 질감, 각도 등)을 포착하세요.\n")
        append("3. **본문 삽입 묘사 포인트**: 블로그 본문 단락 사이에 사진을 넣었을 때 바로 활용할 수 있는 생생하고 매력적인 한 줄 묘사 포인트를 작성해 주세요.\n")
        append("아주 중요 - 사진 속에 글자(차단기나 제품에 적힌 라벨·모델명, 계기판 숫자, 표지판, 지도 속 주소 등)가 보이면 추측하지 말고 눈에 보이는 그대로 정확히 읽어서 반영하세요. 글자가 작거나 흐릿해도 최선을 다해 읽고, 정말 읽을 수 없으면 언급하지 마세요 - 안 보이는 글자를 그럴듯하게 지어내는 건 절대 안 됩니다.\n\n")
        append("반드시 아래 규격의 순수 JSON으로만 출력해 주세요(설명, 인사말, 코드블록 없이 JSON 그 자체만):\n")
        append("{\n")
        append("  \"analyses\": [\n")
        append("    { \"index\": 0, \"subject\": \"사진 속 피사체 요약\", \"visual_description\": \"사진의 실제 시각적 디테일 상세 묘사\" }\n")
        append("  ]\n")
        append("}")
    }

    // 2단계 - 원본 buildUserArticlePromptWithAnalysis를 그대로 옮겼다. photoDescriptions는
    // 1단계(buildVisionAnalysisPrompt) 응답을 파싱한 결과이고, 비어 있으면 안 된다(Activity에서
    // 1단계를 먼저 끝내도록 강제한다).
    fun build(
        keyword: String,
        caseInfo: String?,
        editorialRules: String,
        blogProfile: BlogProfileEntry,
        photoDescriptions: List<String>
    ): String = buildString {
        val calloutLabel = calloutLabelFor(blogProfile.id)
        val hasImages = photoDescriptions.isNotEmpty()
        val lastIdx = (photoDescriptions.size - 1).coerceAtLeast(0)

        // 원본 역할 설명 그대로.
        append("당신은 대한민국 최고 전문 블로그 에디터이자 콘텐츠 기획자입니다.\n")
        append("앞서 분석한 사진들의 시각적 정보와 아래 사용자 입력 정보를 바탕으로, 네이버 블로그/웹에 바로 게시할 수 있는 완벽한 완성본 HTML 포스팅을 작성해 주세요.\n\n")

        // 이 앱만의 레이어: 글 내용은 키워드 현장 정보를 그대로 따른다(사용자 지시).
        append("[제목 키워드]: $keyword\n\n")
        append(editorialRules).append("\n")
        append(PromptBuilder.informationalGuardrails())
        if (!caseInfo.isNullOrBlank()) {
            append(caseInfo).append("\n")
        }

        // 이 앱만의 레이어: 블로그별 문체·소제목 어투 차별화(사용자 지시 - 네이버 공장형
        // 템플릿 그룹 묶임 방지).
        append("[이 블로그만의 문체·소제목 관점 - 다른 블로그와 겹치지 않게 반드시 지켜줘]\n")
        append("${blogProfile.voiceHint}\n")
        append("${blogProfile.openingStyle}\n")
        append("위 문체에 방문, 도착, 현장 작업, 고객 경험을 전제로 한 표현이 있으면 적용하지 말고 순수 정보형 서술을 유지해.\n")
        append("아주 중요 - 소제목 문장도 반드시 위 문체·관점에 맞춰 짓고, 다른 블로그가 쓸 법한 뻔한 틀(예: \"OOO 현장 점검해보니\", \"OOO 교체 작업 진행해봤더니\", \"OOO 최종 완료 후기\")을 그대로 따라 쓰지 마. 어휘와 문장 구조 자체를 이 블로그만의 관점으로 다르게 써줘.\n")
        append("제목 표기: ${blogProfile.titleInstruction}\n\n")

        // 원본 imageCatalog(비-커스텀 모드)를 그대로 옮겼다 - 1단계에서 받은 분석 결과를 "사전
        // 분석 완료된 사진 목록"으로 그대로 보여준다.
        if (hasImages) {
            append("[사전 비전 분석 완료된 사진 목록 (총 ${photoDescriptions.size}장) - 촬영 시간 순서 유지 필수 지침]\n")
            append("앞서 분석한 ${photoDescriptions.size}장의 사진 정보입니다. 사진 번호는 촬영 시간 순서(0번이 가장 이른 시간, 번호가 커질수록 더 최근)로 이미 정렬돼 있습니다:\n")
            photoDescriptions.forEachIndexed { idx, desc ->
                val shown = desc.ifBlank { "현장 사진 (세부 설명 없음)" }
                append("  - [사진 #$idx]: $shown -> 본문에 단독 줄로 <!-- USER_IMAGE_$idx: 사진설명캡션 --> 주석 삽입\n")
            }
            // 원본 딸깍 문구는 "첨부 순서에 얽매이지 말고 서사 흐름에 맞춰 재배치하라"고
            // 하지만, 이 앱은 사진을 촬영 시간 순으로 이미 정렬해뒀고 사용자가 그 순서를 그대로
            // 지키길 원한다(사용자 지적 - "사진이 날짜 시간별로 순차적으로 안 들어가고 있어").
            // AI가 서사 흐름을 이유로 순서를 임의로 바꾸거나 대표 썸네일을 골라 앞으로 빼면 그
            // 순서가 깨지므로, 순서 재배치·대표 썸네일 선정 지시는 빼고 "번호 순서 그대로"로
            // 못박는다.
            append("\n**사진 배치 및 캡션 작성 지침:**\n")
            append("1. ⭐ **번호 순서 절대 유지**: 사진은 0번부터 ${lastIdx}번까지 번호 순서 그대로, 즉 촬영 시간 순서 그대로 본문 위에서 아래로 배치하세요. 서사 흐름이나 완성도를 이유로 순서를 바꾸거나 특정 사진을 앞으로 당기지 마세요. 0번 사진의 USER_IMAGE 주석이 반드시 1번보다 먼저 나와야 하고, 1번은 2번보다 먼저 나와야 합니다 - 이 순서는 절대 깨면 안 됩니다.\n")
            append("   - 각 사진은 그 사진 내용과 가장 관련 있는 소제목 단락 아래에 배치하되, 사진들 사이의 선후 순서(0→1→2→...)는 항상 지키세요.\n")
            append("2. 각 사진 주석 뒤에 콜론(:)과 함께 문맥에 맞는 자연스럽고 생생한 한 줄 캡션을 작성하세요. (예: <!-- USER_IMAGE_1: 슬림한 두께와 견고한 메탈 힌지 마감 -->)\n")
            append("3. 모든 사진 번호 0번~${lastIdx}번이 본문에 빠짐없이 1번씩, 번호 순서대로 반드시 포함되어야 합니다.\n\n")
        } else {
            append("[첨부된 사진 없음: 본문 텍스트 중심으로 작성해 주세요.]\n\n")
        }

        append("[작성 및 서식 요구사항]\n")
        append("1. ⭐ [필수 HTML 태그] 본문의 모든 일반 문단은 반드시 <p> 태그로 감싸고, \"1. ~\", \"2. ~\"처럼 번호가 붙는 큰 소제목은 반드시 예외 없이 아래 스타일 그대로 <h2> 태그로 작성하세요 (굵은 글씨는 오직 이 h2에만 적용):\n")
        append("   <h2 style=\"border: 2px solid $ACCENT; background-color: $HEADING_BG; padding: 15px; font-size: 28px; color: $HEADING_TEXT; font-weight: bold; margin: 20px 0;\">1. 소제목</h2>\n")
        append("   (글자색만 있고 테두리·배경 박스가 없는 민짜 소제목은 금지입니다!) 큰 소제목 안에서 더 작은 하위 항목이 필요할 때만 <h3 style=\"color: $HEADING_TEXT;\">처럼 글자색만 적용한 h3를 보조적으로 쓰세요. (태그 없이 일반 줄글만 작성하면 안 됩니다!)\n")
        if (hasImages) {
            append("2. ⭐ [필수 사진 배치] 사전 분석된 각 사진이 본문에서 설명되는 단락 바로 앞이나 뒤에 반드시 <!-- USER_IMAGE_0 --> ~ <!-- USER_IMAGE_${lastIdx} --> 주석을 단독 줄로 삽입하세요. (공백이나 줄바꿈으로 비워두지 마세요!)\n")
        }
        append("3. 한 문단은 3~4문장으로 구성하고, 핵심 키워드나 중요한 문장만 <strong> 또는 <span style='background-color: $HIGHLIGHT_BG; padding: 2px 4px; border-radius: 3px;'>형광펜</span> 스타일로 강조하세요. 한 문단에 강조는 최대 1개만 사용하세요.\n")
        append("3-1. 본문은 제목을 제외하고 공백 포함 1600자 이상으로 작성하세요.\n")
        append("4. 나열할 정보는 <ul>과 <li>를 사용하세요.\n")
        append("4-1. ⭐⭐ [필수] 스펙 비교, 가격·옵션 비교, 전/후 비교, 점검 항목 정리처럼 항목별로 값을 나란히 정리하는 내용이 하나라도 있으면 절대 생략하지 말고 반드시 진짜 HTML <table> 태그를 아래 형태 그대로 사용하세요 (행마다 배경색을 교차 적용):\n")
        append("   <table style=\"border-collapse: collapse; width: 100%; border: 1px solid #ccc;\"><tbody><tr style=\"background-color: #f5f5f5;\"><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left; white-space: nowrap;\"><b>항목1</b></td><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left;\">내용1</td></tr><tr style=\"background-color: #fafafa;\"><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left; white-space: nowrap;\"><b>항목2</b></td><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left;\">내용2</td></tr></tbody></table>\n")
        append("   ⭐ <p>나 <div>에 배경색만 칠해서 표처럼 \"보이게\" 흉내 내는 것은 금지입니다 — 반드시 실제 <table> 태그 구조여야 합니다. 표는 본문 중간에 설명 없이 뜬금없이 끼워 넣지 말고, 반드시 해당 h2 소제목 바로 아래에 배치해서 그 소제목에 속한 내용이라는 게 명확히 보이도록 하세요.\n")
        append("5. 유용한 팁이나 주요 포인트는 아래처럼 콜아웃 박스로 꾸며주세요(제목은 반드시 \"$calloutLabel\"로 써 - 다른 문구로 바꾸지 마세요):\n")
        append("   <div style=\"background-color: $CALLOUT_BG; border-left: 4px solid $ACCENT; color: $CALLOUT_TEXT; padding: 12px 16px; border-radius: 6px;\"><strong>$calloutLabel</strong><br>내용...</div>\n")
        append("6. 사진 캡션은 25자 안팎의 담백한 설명으로 작성하고 이모지는 넣지 마세요. ⭐ 사용자가 원고료·협찬·제품제공 등을 받았다고 명시적으로 밝히지 않는 한, 광고·협찬 고지 문구를 임의로 지어내 넣지 마세요. ⭐ 파일명이나 숫자, \"설명 없음\" 같은 문구를 캡션으로 그대로 쓰지 말고, 반드시 사진 속 실제 모습을 눈으로 보고 묘사한 문장으로 작성하세요.\n")
        append("7. 반드시 아래 예시와 같이 html 코드블록 안에 상단 메타데이터 주석과 본문 HTML 코드를 작성해 주세요(설명이나 인사말은 코드블록 밖에 쓰지 마세요):\n\n")

        append("[출력 형식 예시]\n")
        append("```html\n")
        append("<!-- TITLE: 클릭을 부르는 매력적인 블로그 제목 -->\n")
        append("<!-- SUMMARY: 글의 핵심 요약 1~2문장 -->\n")
        append("<!-- TAGS: 추천태그1, 추천태그2, 추천태그3, 추천태그4, 추천태그5 -->\n\n")
        append("<p>도입부 공감 문단 내용...</p>\n\n")
        if (hasImages) append("<!-- USER_IMAGE_0: 캡션 -->\n\n")
        append("<h2 style=\"border: 2px solid $ACCENT; background-color: $HEADING_BG; padding: 15px; font-size: 28px; color: $HEADING_TEXT; font-weight: bold; margin: 20px 0;\">1. 소제목</h2>\n")
        append("<p>소제목 아래 상세 본문 단락...</p>\n")
        if (hasImages) {
            append("\n<!-- USER_IMAGE_1: 캡션 -->\n\n")
        }
        append("<h2 style=\"border: 2px solid $ACCENT; background-color: $HEADING_BG; padding: 15px; font-size: 28px; color: $HEADING_TEXT; font-weight: bold; margin: 20px 0;\">2. 항목별 비교(예: 스펙, 가격, 전후 비교 등)</h2>\n")
        append("<table style=\"border-collapse: collapse; width: 100%; border: 1px solid #ccc;\"><tbody><tr style=\"background-color: #f5f5f5;\"><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left; white-space: nowrap;\"><b>항목1</b></td><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left;\">내용1</td></tr><tr style=\"background-color: #fafafa;\"><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left; white-space: nowrap;\"><b>항목2</b></td><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left;\">내용2</td></tr></tbody></table>\n")
        append("```")
    }
}
