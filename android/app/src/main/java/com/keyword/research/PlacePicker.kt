package com.keyword.research

// 서울/경기도 구·동 무작위 뽑기. ElectricBlogClipboard(전기수리 블로그 앱) 프로젝트에서 이미 만들어
// 둔 것과 똑같은 데이터/방식을 그대로 이식했다 - 지역(서울/경기)을 먼저 고르면 그 안에서 구(또는 시)
// +동을 무작위로 뽑아 출장 사례 정보의 지역/동 칸에 채워준다.
data class Place(val district: String, val dong: String)

// 구(또는 시) 하나와 그 안의 동 목록 - "동별 경쟁 낮은 곳 찾기"에서 구를 고르거나 구 단위로 훑을 때 쓴다.
data class DistrictGroup(val district: String, val dongs: List<String>)

enum class Region(val label: String) { SEOUL("서울"), GYEONGGI("경기") }

object PlacePicker {

    private val SEOUL_GROUPS: List<Pair<String, List<String>>> = listOf(
        "강남구" to listOf("역삼동", "삼성동", "청담동", "논현동", "신사동", "대치동", "도곡동", "개포동", "일원동", "세곡동"),
        "서초구" to listOf("서초동", "반포동", "잠원동", "방배동", "양재동", "내곡동"),
        "송파구" to listOf("잠실동", "신천동", "문정동", "가락동", "방이동", "오금동", "거여동", "위례동"),
        "강동구" to listOf("천호동", "성내동", "길동", "암사동", "명일동", "고덕동", "상일동", "둔촌동"),
        "강서구" to listOf("화곡동", "등촌동", "염창동", "가양동", "방화동", "발산동", "마곡동"),
        "양천구" to listOf("목동", "신월동", "신정동"),
        "영등포구" to listOf("여의도동", "당산동", "영등포동", "문래동", "신길동", "양평동", "대림동"),
        "구로구" to listOf("구로동", "신도림동", "개봉동", "고척동", "오류동", "항동"),
        "금천구" to listOf("가산동", "시흥동", "독산동"),
        "동작구" to listOf("노량진동", "상도동", "흑석동", "사당동", "대방동", "신대방동"),
        "관악구" to listOf("봉천동", "신림동", "남현동"),
        "서대문구" to listOf("신촌동", "홍제동", "홍은동", "남가좌동", "북가좌동", "연희동", "냉천동"),
        "마포구" to listOf("성산동", "상암동", "합정동", "망원동", "연남동", "공덕동", "아현동", "서교동", "대흥동"),
        "은평구" to listOf("불광동", "갈현동", "구산동", "응암동", "역촌동", "신사동", "진관동"),
        "종로구" to listOf("창신동", "숭인동", "명륜동", "혜화동", "평창동", "무악동"),
        "중구" to listOf("신당동", "황학동", "장충동", "필동", "을지로동"),
        "용산구" to listOf("이촌동", "한남동", "서빙고동", "원효로동", "청파동", "효창동", "보광동"),
        "성동구" to listOf("성수동", "옥수동", "금호동", "행당동", "왕십리동", "응봉동"),
        "광진구" to listOf("광장동", "자양동", "구의동", "화양동", "중곡동", "능동"),
        "동대문구" to listOf("답십리동", "장안동", "이문동", "회기동", "전농동", "청량리동"),
        "중랑구" to listOf("면목동", "상봉동", "중화동", "묵동", "신내동"),
        "성북구" to listOf("정릉동", "길음동", "돈암동", "장위동", "안암동", "종암동"),
        "강북구" to listOf("미아동", "번동", "수유동"),
        "도봉구" to listOf("창동", "쌍문동", "방학동", "도봉동"),
        "노원구" to listOf("상계동", "중계동", "하계동", "공릉동", "월계동")
    )

    private val GYEONGGI_GROUPS: List<Pair<String, List<String>>> = listOf(
        "장안구" to listOf("정자동", "율전동"), "권선구" to listOf("권선동", "구운동"),
        "팔달구" to listOf("인계동", "매교동"), "영통구" to listOf("영통동", "매탄동", "망포동"),
        "수정구" to listOf("태평동", "신흥동"), "중원구" to listOf("성남동", "금광동"),
        "분당구" to listOf("정자동", "서현동", "야탑동", "수내동", "판교동", "이매동"),
        "처인구" to listOf("김량장동", "역북동"), "기흥구" to listOf("구갈동", "동백동", "보정동"),
        "수지구" to listOf("수지동", "죽전동", "상현동", "풍덕천동"),
        "덕양구" to listOf("행신동", "화정동", "능곡동"), "일산동구" to listOf("장항동", "마두동", "백석동", "정발산동"),
        "일산서구" to listOf("주엽동", "일산동", "탄현동"),
        "만안구" to listOf("안양동", "박달동"), "동안구" to listOf("평촌동", "비산동", "관양동", "호계동"),
        "상록구" to listOf("사동", "본오동", "월피동"), "단원구" to listOf("고잔동", "원곡동", "초지동"),
        "부천시" to listOf("중동", "상동", "중앙동", "심곡동", "원미동", "송내동"),
        "남양주시" to listOf("다산동", "별내동", "평내동", "진접읍"),
        "화성시" to listOf("동탄동", "병점동", "향남읍", "봉담읍"),
        "평택시" to listOf("비전동", "송탄동", "서정동", "안중읍"),
        "의정부시" to listOf("신곡동", "민락동", "호원동", "장암동"),
        "시흥시" to listOf("정왕동", "은행동", "배곧동", "목감동"),
        "파주시" to listOf("금촌동", "운정동", "문산읍"),
        "김포시" to listOf("사우동", "풍무동", "장기동", "구래동"),
        "광명시" to listOf("철산동", "하안동", "광명동", "소하동"),
        "군포시" to listOf("산본동", "금정동", "당동"),
        "이천시" to listOf("창전동", "부발읍"),
        "양주시" to listOf("덕정동", "회천동", "옥정동"),
        "오산시" to listOf("오산동", "세교동"),
        "구리시" to listOf("인창동", "수택동", "교문동"),
        "안성시" to listOf("봉산동", "공도읍"),
        "포천시" to listOf("신읍동", "소흘읍"),
        "의왕시" to listOf("내손동", "포일동", "청계동"),
        "하남시" to listOf("신장동", "미사동", "덕풍동"),
        "여주시" to listOf("여흥동", "교동"),
        "동두천시" to listOf("생연동", "지행동"),
        "과천시" to listOf("별양동", "중앙동", "부림동"),
        "가평군" to listOf("가평읍", "설악면"),
        "양평군" to listOf("양평읍", "강상면"),
        "연천군" to listOf("연천읍", "전곡읍"),
        "광주시" to listOf("경안동", "오포읍", "태전동")
    )

    private fun groupsFor(region: Region): List<Pair<String, List<String>>> =
        when (region) { Region.SEOUL -> SEOUL_GROUPS; Region.GYEONGGI -> GYEONGGI_GROUPS }

    fun districtGroups(region: Region): List<DistrictGroup> =
        groupsFor(region).map { (district, dongs) -> DistrictGroup(district, dongs) }

    fun placesFor(region: Region): List<Place> =
        groupsFor(region).flatMap { (district, dongs) -> dongs.map { Place(district, it) } }

    fun random(region: Region): Place = placesFor(region).random()
}

// 작업 종류 목록 - 마찬가지로 ElectricBlogClipboard의 BlogTextGenerator.WORK_TYPES와 동일.
object WorkTypes {
    val LIST = listOf(
        "누전 점검", "절연저항 측정", "회로별 전원 점검", "전기 배선 점검", "전기 배선 수리",
        "분전반 점검", "누전 차단기 점검", "배선용 차단기 점검", "차단기 교체", "분전반 교체",
        "정전 원인 점검", "정전 복구", "콘센트 점검", "콘센트 교체", "콘센트 증설",
        "스위치 점검", "스위치 교체", "조명 회로 점검", "등기구 교체", "전용선 배선 공사",
        "에어컨 전용선 공사", "인덕션 전용선 공사", "세탁기·건조기 전용선 공사", "전기차 충전기 배선 공사",
        "접지 점검", "접지 공사", "전기 용량 점검", "전기 용량 증설", "종합 전기 안전점검"
    )
}
