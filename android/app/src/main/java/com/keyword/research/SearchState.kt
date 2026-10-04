package com.keyword.research

// 키워드 검색이 별도 화면(KeywordSearchActivity)으로 분리되면서, 메인 화면의 "요청문 만들기"/
// "AI 글 초안 생성"이 마지막 검색 결과를 계속 참조할 수 있도록 앱 프로세스 동안만 유지되는
// 간단한 메모리 저장소. ApiKeyStore와 달리 디스크에 저장하지 않는다(세션 데이터라 필요 없음).
object SearchState {
    var lastKeyword: String = ""
    var lastCompetitorPosts: List<NaverBlogSearchApi.BlogPost> = emptyList()

    // lastCompetitorPosts가 실제로 어떤 키워드로 검색된 결과인지 기억해 둔다. 메인 화면의
    // "키워드 찾기" 필드에서 새 키워드로 검색 버튼을 누르면 lastKeyword만 먼저 바뀌므로, 이 값과
    // lastKeyword가 다르면(=아직 이 키워드로 검색 안 됨) KeywordSearchActivity가 자동으로 검색을
    // 시작한다 - "키워드 검색을 누르면 키워드 검색 버튼 화면 안에서 결과가 나오게" 해달라는 요청.
    var lastSearchedKeyword: String = ""

    // 키워드 분석(KeywordAnalysisActivity) 결과도 같은 이유로 메모리에만 잠깐 들고 있는다 - 메인
    // 화면의 "단계별 키워드 가져오기"가 이 목록을 그대로 쓴다. lastAnalysisKeyword는 이 결과가
    // 어떤 키워드로 뽑은 것인지 기억해서, 키워드가 바뀌면 재분석이 필요하다는 걸 알 수 있게 한다.
    var lastAnalysisKeyword: String = ""
    var lastAnalysisResults: List<NaverSearchAdApi.RelatedKeyword> = emptyList()

    // 메인 화면 "단계별 키워드 가져오기"에서 고른 단계의 키워드 목록. 이 값이 채워져 있으면
    // 키워드 검색 화면은 (직접 입력한 키워드의 자동완성 대신) 이 목록으로 경쟁사 글목록 검색을
    // 한다. 키워드 검색 화면에서 "검색" 버튼을 누르면 이 값은 전부 비워진다(휘발성 - 키워드
    // 검색과 단계별 가져오기가 같은 화면을 두고 서로 지운다).
    var stagedKeywordStage: Int = 0
    var stagedKeywords: List<NaverSearchAdApi.RelatedKeyword> = emptyList()

    // "동별 경쟁 낮은 곳 찾기" 화면(별도 아이콘)에서 결과를 고르면 메인 화면으로 화면 전환 없이
    // 가지고 돌아갈 지역/동 - 메인 화면 onResume에서 이 값을 읽어 지역/동 칸에 채우고 비운다.
    var pickedDistrict: String? = null
    var pickedDong: String? = null

    // "콘텐츠 생성"이 메인 화면에서 "블로그 반자동 복사"(RichCopyTestActivity) 화면으로 옮겨가면서
    // (사용자 요청 - 생성 결과 상자가 두 화면에 중복돼 있던 걸 하나로 합침), 블로그/스타일 선택은
    // 메인 화면의 작은 박스에 남지만 그 선택값은 여기 보관해서 반자동 복사 화면이 읽어 쓴다.
    var selectedStyle: WritingStyle = WritingStyle.FRIENDLY_REVIEW
    var selectedBlogId: Int = 1

    // "키워드와 현장 정보"도 메인 화면에서 별도 화면(KeywordCaseInfoActivity)으로 옮겨갔다(카드
    // 대시보드 방식 - 사용자 요청). 그 화면을 나갈 때마다(onPause) 지금 값으로 다시 만든 사례
    // 정보 블록을 여기 스냅샷으로 저장해두고, 메인 화면의 "블로그 반자동 복사"는 이걸 그대로
    // 읽어 쓴다. null이면 해당 모드에 쓸 사실 정보가 비어있다는 뜻이다.
    var caseInfoBlock: String? = null
    var informationalCaseInfoBlock: String? = null
    var hasLocationInfo: Boolean = false
    var dong: String = ""
}
