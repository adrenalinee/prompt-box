# PromptBox

프롬프트를 테스트하고 관리할 수 있습니다.

# modules
- llm-stream-client: llm router 입니다. 공통 인터페이스로 llm provider(google, openai, xai, ..) api 를 호출하게 할 수 있습니다.
- root: llm api 를 호출해보고 각 벤더별, 모델별로 응답 내용, 속도등을 비교해볼 수 있는 웹 툴을 위한 api 서버입니다.


# feature
## llm-stream-client
- 여러 provider 의 llm api 를 호출할 수 있는 공통 request, response 포맷 지원
- openai responses api streaming mode 를 베이스로 설계. (streaming mode 만 지원)
- 현재 openai, xai, google 용 클라이언트 지원.
- fakeLlmStreamClient (실제 API 호출 없이 미리 준비한 메시지를 순서대로 스트리밍하는 테스트용 구현체.) 지원.
## prompt-box
- playground
  - llm api 를  호출하고 streaming 방식 응답을 받을 수 있습니다.
  - 여러 벤더의 모델을 호출해볼 수 있습니다.
- compare (진행중)
  - 같은 (혹은 다른) 프롬프트와 옵션으로 여러 모델에 동시 요청을 보낼 수 있습니다.
  - 각 응답결과를 비교할 수 있습니다. (diff)
- named prompt
  - 한번 작성한 잘 만든 프롬프트를 저장해놓고 계속 사용할 수 있습니다. 
  - 프롬프트의 버전관가 가능합니다.
- llm call logs
  - llm api 를 호출한 내역을 확인할 수 있습니다.
- workspace
  - 관심사별로 데이터를 나눌 수 있습니다.
  - 여러가지 옵션을 별도관리할 수 있습니다.


# 로드맵
## llm-stream-client
- vendor 추가 지원
  - anthropic

## prompt-box
- 개인화
  - 롤기반 권한관리(하위롤의 모든 권한을 가짐)
    - 플랫폼 관리자 - global 설정 write 가능
    - 플랫폼 사용자 - global 설정 read 가능
    - 워크스페이스 관리자 - 개별 워크스페이스 공통 설정 write 가능
    - 워크스페이스 사용자 - 개별 워크스페이스 write 가능. playground 사용, named prompt 생성, 공통 설정 read 가능
    - 워크스페이스 게스트 - 개별 워크스페이스 read 만 가능
  - 개인화된 설정 지원
    - api key
    - default model
    - default option
- playground (new)
  - 대화 이어가기 지원
  - markdown 포맷으로 output data 를 볼 수 있게 지원
- prompt
  - 변수 지원
  - function call 지원
- prompt  platform 으로 진화
  - named prompt 를 다른 프로젝트에서 사용할 수 있게 지원해서 프롬프트 플랫폼화


