// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android
fun main() {
    var n=0
    fun verify(ok:Boolean) { check(ok) { "assertion ${n+1}" }; n++ }
    fun bytes(s:String)=s.toByteArray().size
    val a = TeleprompterSpeechMatcher("안녕하세요. 오늘은 공사 계약을 설명합니다. 다음은 일정입니다.")
    val first=a.match("안녕하세요")!!
    verify(first.byteOffset==bytes("안녕하세요")); verify(first.confidence==1.0)
    verify(a.match("안녕하세요")==null); verify(a.match("안녕하세요",true)==null)
    val second=a.match("오늘은 공사 계약을 설명합니다",true)!!
    verify(second.byteOffset==bytes("안녕하세요. 오늘은 공사 계약을 설명합니다"))
    verify(a.match("완전히 다른 외부 대화",true)==null)
    verify(a.match("다음은 일정입니다",true)!!.byteOffset==bytes("안녕하세요. 오늘은 공사 계약을 설명합니다. 다음은 일정입니다"))
    val b=TeleprompterSpeechMatcher("😀 ABCD. ＥＦＧＨ café. 끝입니다.")
    verify(b.match("abcd",true)!!.byteOffset==bytes("😀 ABCD"))
    verify(b.match("efgh cafe",true)!!.byteOffset==bytes("😀 ABCD. ＥＦＧＨ café"))
    verify(b.match("끝입니다",true)!!.byteOffset==bytes("😀 ABCD. ＥＦＧＨ café. 끝입니다"))
    val c=TeleprompterSpeechMatcher("안녕하세요 안녕하세요 다른 문장")
    verify(c.match("안녕하세요")!!.byteOffset==bytes("안녕하세요"))
    verify(c.match("안녕하세요",true)==null); verify(c.match("안녕하세요",true)==null)
    c.seek(bytes("안녕하세요 안녕하세요 ")); verify(c.match("다른 문장",true)!!.byteOffset==bytes("안녕하세요 안녕하세요 다른 문장"))
    val d=TeleprompterSpeechMatcher("abcdefghijklmnopqrstuvwxyz "+"x".repeat(300)+"나중 문장입니다")
    verify(d.match("나중 문장입니다",true)==null); verify(d.match("abc",true)==null)
    d.seek(d.totalBytes); verify(d.match("나중 문장입니다",true)==null)
    val e=TeleprompterSpeechMatcher("현장 공정회의 자료를 설명합니다")
    val fuzzy=e.match("현장 공정회의 자료를 설명함니다",true)
    verify(fuzzy!=null && fuzzy.confidence>=.72)
    val lines=intArrayOf(0,12,24,48)
    for ((offset,page) in listOf(0 to 0, 11 to 0,12 to 0,24 to 12,47 to 12,48 to 24,100 to 24)) verify(TeleprompterSpeechMatcher.pageStart(lines,offset)==page)
    verify(TeleprompterSpeechMatcher("").match("안녕하세요")==null)
    println("$n assertions passed")
}
