package com.keyword.research

// 사용자가 제공한 "단계별 키워드 공략 로드맵"을 그대로 구현한 것.
// 1단계 (지수 쌓기 - 롱테일 정보성): 검색량 100~300건, 경쟁도 낮음~중간.
// 2단계 (지수 확장 - 중형/지역 키워드): 검색량 300~1,000건.
// 3단계 (상위 유지 - 대형 메인 키워드): 검색량 1,000건 이상.
// 검색량이 너무 적은(100 미만) 키워드는 유입/체류시간에 도움이 안 되므로 로드맵의 "수칙"에 따라
// 어느 단계에도 넣지 않는다(전부 제외).
object KeywordStage {

    // 네이버 검색광고 API는 월 검색량이 아주 적으면 숫자 대신 "< 10" 같은 문자열을 준다.
    // 숫자만 남겨서 파싱하고, 못 읽으면 0으로 취급한다.
    private fun parseVolume(raw: String): Int = raw.filter { it.isDigit() }.toIntOrNull() ?: 0

    fun totalVolume(keyword: NaverSearchAdApi.RelatedKeyword): Int =
        parseVolume(keyword.monthlyPcQc) + parseVolume(keyword.monthlyMobileQc)

    // 1/2/3단계에 해당하면 그 번호를, 어디에도 해당하지 않으면 null을 반환한다.
    fun stageOf(keyword: NaverSearchAdApi.RelatedKeyword): Int? {
        val total = totalVolume(keyword)
        return when {
            total in 100..300 && keyword.compIdx != "높음" -> 1
            total in 301..1000 -> 2
            total > 1000 -> 3
            else -> null
        }
    }

    // 해당 단계에 속하는 키워드만 골라 검색량이 많은 순으로 정렬한다(순위).
    fun keywordsForStage(results: List<NaverSearchAdApi.RelatedKeyword>, stage: Int): List<NaverSearchAdApi.RelatedKeyword> =
        results.filter { stageOf(it) == stage }.sortedByDescending { totalVolume(it) }
}
