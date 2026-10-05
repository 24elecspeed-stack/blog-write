package com.keyword.research

// "사진으로 글쓰기" - 크롬 확장프로그램 딸깍 SNS(ddalkkak-extracted/photo-writer/sidepanel.js의
// buildVisionBatchPrompt + buildUserArticlePromptWithAnalysis)가 실제로 쓰는 문구를 최대한 그대로
// 옮겨왔다(사용자 지시 - "명령문을 원래 딸깍 명령문으로 100프로 다 바꿔"). 원본과 똑같이 2단계로
// 나눴다(사용자 지적 - "익스텐션은 먼저 사진을 분석하고 난뒤에 분석 글을 가지고 글을 써. 우리는
// 하나만 하고 있어 - 분석 후 글쓰기를 해야 해"):
//   1단계(buildVisionAnalysisPrompt) - 사진만 보고 JSON으로 피사체·시각 디테일을 분석해 받는다.
//   2단계(build) - 1단계에서 받은 분석 결과를 "사전 분석된 사진 목록"으로 그대로 넣고 글을 쓴다
//                  (원본의 imageCatalog 비-커스텀 모드 블록과 동일한 형태).
// 지도 사진 글자(주소·핀 라벨 등)만 "흐릿해도 최선을 다해 정확히" 읽으라는 원본 문구를 한 번
// 사진 전반(라벨·계기판·제품명 등)으로 넓혔었는데, 그러면서 제미나이가 분석을 너무 길게 쓰기
// 시작해서(사용자 지적 - "계속해서 길게 나오네") 원본처럼 지도 글자로만 범위를 다시 좁혔다
// (사용자 지시 - "원래 딸깍 사진 ocr하는 명령문으로 다시 돌려").
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

    // 원본 cleanFileName(sidepanel.js:485) 그대로 - 확장자 제거 → 앞쪽 숫자+구분자 제거 →
    // 남은 _/- 를 공백으로 → trim. "20241201_161517.jpg" -> "161517".
    private fun cleanFileName(filename: String): String {
        if (filename.isBlank()) return ""
        return filename
            .replace(Regex("\\.[a-zA-Z0-9]+$"), "")
            .replace(Regex("^[0-9]+[_\\s.-]*"), "")
            .replace(Regex("[_\\-]+"), " ")
            .trim()
    }

    // 1단계 - 원본 buildVisionBatchPrompt(sidepanel.js:1327)를 그대로 옮겼다(10장 단위 배치
    // 전송은 수동 흐름에 맞지 않아 뺐다 - 사용자가 한 번에 전부 첨부한다). 처음 포팅할 때
    // topic/content(핵심 내용), 사진 파일 목록, map_location JSON 필드가 전부 빠져서 AI가
    // 주제와 무관한 일반적인 묘사("깔끔한 하얀색 타일 벽면에...")를 썼다(사용자 지적 - "캡션이
    // 엄청 길잖아" / "전기수리에 대한 이야기만 쓰라고 해" / 실제 원본 출력 전문을 붙여넣으며
    // "이게 원본인데 어디서 가지고 온거야"). 사용자가 붙여넣은 실제 원본 출력과 한 글자씩
    // 대조해서 그대로 복구했다. 끝의 "[중요] Canvas나 몰입형 문서..." 문구는 buildVisionBatchPrompt
    // 자체가 아니라 background.js:631(sendPromptToGemini)에서 Gemini로 보낼 때 자동으로
    // 덧붙이는 문구인데, 원본이 실제로 Gemini에 보내는 최종 텍스트에는 포함되므로 그대로 넣는다.
    fun buildVisionAnalysisPrompt(
        photoCount: Int,
        topic: String = "",
        content: String = "",
        photoNames: List<String> = emptyList()
    ): String = buildString {
        append("당신은 세계 최고 수준의 멀티모달 비전 AI이자 전문 블로그 콘텐츠 기획자입니다.\n")
        append("작성 예정인 블로그 글의 주제와 핵심 내용을 바탕으로, 대화창에 첨부된 ${photoCount}장의 사진을 순서대로 직접 눈으로 확인하고 **글의 맥락에 맞춰 사진의 시각적 특징을 정확하고 생생하게 분석**해 주세요.\n")
        if (topic.isNotBlank() || content.isNotBlank()) {
            append("\n[작성 예정인 블로그 글 정보 (시각 분석 참조 맥락)]\n")
            append("- 포스팅 주제/키워드: ${topic.ifBlank { "블로그 포스팅" }}\n")
            if (content.isNotBlank()) {
                val brief = if (content.length > 800) content.take(800) + "... (이하 생략)" else content
                append("- 작성할 핵심 내용 및 컨셉:\n$brief\n")
            }
            append("\n")
        }
        append("[첨부된 사진 목록]\n")
        for (i in 0 until photoCount) {
            val name = photoNames.getOrNull(i).orEmpty()
            val clean = cleanFileName(name)
            val nameInfo = if (name.isNotBlank()) " (파일명: $name${if (clean.isNotBlank()) " / 정제키워드: $clean" else ""})" else ""
            append("- [사진 #$i]: 대화창 첨부 순서 ${i + 1}번째 사진$nameInfo\n")
        }
        append("\n[맥락 맞춤형 시각 분석 지침]\n")
        append("각 사진에 대해:\n")
        append("1. **맥락에 부합하는 피사체 파악**: 작성할 글 정보(제품명, 브랜드, 매장 등)를 고려하여 실제로 사진에 찍힌 피사체와 장면을 정확히 식별하세요. (예: 단순 '노트북'이 아닌 '갤럭시북4 메탈 그레이 상판 디자인', 단순 '연결 구멍'이 아닌 '풀사이즈 HDMI 및 C타입 단자 구성')\n")
        append("2. **블로그 서사용 시각 디테일**: 글에서 독자의 공감을 이끌 수 있는 핵심 시각적 특징(색감, 질감, 마감 디테일, 화면 선명도, 힌지 각도 등)을 포착하세요.\n")
        append("3. **본문 삽입 묘사 포인트**: 블로그 본문 단락 사이에 사진을 넣었을 때 바로 활용할 수 있는 생생하고 매력적인 한 줄 묘사 포인트를 작성해 주세요.\n")
        append("4. **지도 사진 판별**: 사진이 네이버지도·카카오맵·구글맵 등 지도(위치 검색) 화면 캡처인지 하나씩 확인하세요. 지도 화면이 맞다면, 화면에 보이는 텍스트(마커 옆 주소, 상단 검색창, 핀 라벨 등)에서 \"시/도 + 구/군 + 동/읍/면\" 중 읽을 수 있는 만큼 최대한 정확히 읽어 \"map_location\" 필드에 적으세요. 글자가 작거나 흐릿해도 최선을 다해 읽어 보고, 일부만 보이면 보이는 부분만이라도 적으세요. 지도 화면이 아니면 \"map_location\"은 반드시 null로 두세요.\n\n")
        append("반드시 아래 규격의 순수 JSON으로만 출력해 주세요:\n")
        append("{\n")
        append("  \"analyses\": [\n")
        append("    {\n")
        append("      \"index\": 0,\n")
        append("      \"subject\": \"맥락 기반 피사체 요약\",\n")
        append("      \"visual_description\": \"글 흐름에 최적화된 사진의 실제 시각적 디테일 상세 묘사\",\n")
        append("      \"map_location\": \"지도 사진인 경우 읽은 지역명(예: 서울 강남구 역삼동), 지도가 아니면 null\"\n")
        append("    }\n")
        append("  ]\n")
        append("}\n\n")
        append("[중요] 반드시 일반 채팅 말풍선 안에 ```html 코드블록으로만 답변하세요. Canvas나 몰입형 문서(Immersive) 모드를 사용하지 마세요.")
    }

    // 2단계 - 원본 buildUserArticlePromptWithAnalysis를 그대로 옮겼다. photoDescriptions는
    // 1단계(buildVisionAnalysisPrompt) 응답을 파싱한 결과이고, 비어 있으면 안 된다(Activity에서
    // 1단계를 먼저 끝내도록 강제한다).
    fun build(
        keyword: String,
        caseInfo: String?,
        editorialRules: String,
        blogProfile: BlogProfileEntry,
        photoDescriptions: List<String>,
        districtDong: String? = null,
        informational: Boolean = true
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
        // "반자동 글쓰기"(RichCopyTestActivity/WritingStyle.PromptBuilder.build())와 동일하게
        // informational일 때만 붙인다(사용자 요청 - "반자동 글쓰기처럼 선택을 해야 글을
        // 쓰게끔 해줘" - 출장 사례형/정보형을 고르게 하면서 발견한 버그: 이전엔 이 함수가
        // 항상 정보성 모드 전용이라 informationalGuardrails()를 무조건 붙였는데, 그대로 두면
        // 출장 사례형을 골라도 "출장 서술 금지" 지침이 섞여 들어간다).
        if (informational) {
            append(PromptBuilder.informationalGuardrails())
        }
        if (!caseInfo.isNullOrBlank()) {
            append(caseInfo).append("\n")
        }

        // 이 앱만의 레이어: 블로그별 문체·소제목 어투 차별화(사용자 지시 - 네이버 공장형
        // 템플릿 그룹 묶임 방지).
        append("[이 블로그만의 문체·소제목 관점 - 다른 블로그와 겹치지 않게 반드시 지켜줘]\n")
        append("${blogProfile.voiceHint}\n")
        append("${blogProfile.openingStyle}\n")
        // 정보형일 때만 "방문/도착/현장 경험" 식 표현을 빼고 순수 정보 서술로 바꾼다 - 출장
        // 사례형은 반대로 실제 방문·작업 경험을 그대로 써야 하므로 이 지침을 넣지 않는다.
        if (informational) {
            append("위 문체에 방문, 도착, 현장 작업, 고객 경험을 전제로 한 표현이 있으면 적용하지 말고 순수 정보형 서술을 유지해.\n")
        }
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
        // 원본의 mapLocation 제목 접두 지시(sidepanel.js:1635) 그대로 - 사진 EXIF GPS로
        // 구한 "구 동"을 제목 맨 앞에 붙인다(사용자 요청 - "정보성글 구동을 고르게 해줘").
        // caseInfo(informationalCaseInfoBlock)는 "특정 지역을 특정하지 말라"고 지시하므로,
        // 지역 사실을 캐이스 정보 쪽에 섞지 않고 원본처럼 제목 접두사로만 분리해서 반영한다.
        if (!districtDong.isNullOrBlank()) {
            append("1-1. ⭐ [필수] 제목 맨 앞에 지역명 \"$districtDong \"을 반드시 그대로 붙이세요. (예: \"$districtDong $keyword\"처럼 지역명 뒤에 공백을 두고 나머지 제목을 이어 쓰세요. 지역명 글자를 바꾸거나 줄이지 마세요.)\n")
        }
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
        // 네이버에 실제로 발행된 화면을 보면 #태그가 TAGS 주석뿐 아니라 본문 맨 끝에도 문단으로
        // 그대로 적혀 있었다(사용자가 실기기 캡처로 확인) - 별도 자동화가 아니라 AI가 본문 글
        // 자체에 해시태그 줄을 써낸 것이었다. 우리 프롬프트엔 이 지시가 없어서 안 나왔으므로
        // (사용자 지적 - "태그 자동으로 넣어줘") 똑같이 본문 마지막 문단으로 쓰라고 명시한다 -
        // 그러면 캡션처럼 반자동 배치·복사 흐름에 자동으로 같이 실린다.
        append("6-1. ⭐ 본문의 맨 마지막 문단으로, TAGS 주석과 같은 태그들을 \"#태그1 #태그2 #태그3 #태그4 #태그5\" 형태로 <p> 태그에 담아 한 줄 추가하세요.\n")
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
        append("<table style=\"border-collapse: collapse; width: 100%; border: 1px solid #ccc;\"><tbody><tr style=\"background-color: #f5f5f5;\"><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left; white-space: nowrap;\"><b>항목1</b></td><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left;\">내용1</td></tr><tr style=\"background-color: #fafafa;\"><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left; white-space: nowrap;\"><b>항목2</b></td><td style=\"padding: 8px; border: 1px solid #ccc; text-align: left;\">내용2</td></tr></tbody></table>\n\n")
        append("<p>#추천태그1 #추천태그2 #추천태그3 #추천태그4 #추천태그5</p>\n")
        append("```")
    }
}
