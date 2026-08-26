# 在线朗读规则说明

* 在线朗读规则为url规则,同书源url
* js参数
```
speakText //朗读文本
speakSpeed //朗读速度,5-50
currentToneID //当前分角色朗读选中的发言人 toneID,没有配置时为空
currentSpeakerName //当前分角色朗读选中的发言人展示名
currentEmotionName //当前分角色朗读选中的情绪名
currentEmotionTag //当前分角色朗读选中的情绪标志,例如[高兴]
currentSpeechRouteJson //当前配音路由完整JSON
```
* 例:
```
http://tts.baidu.com/text2audio,{
    "method": "POST",
    "body": "tex={{java.encodeURI(java.encodeURI(speakText))}}&spd={{String((speakSpeed + 5) / 10 + 4)}}&per=5003&cuid=baidu_speech_demo&idx=1&cod=2&lan=zh&ctp=1&pdt=1&vol=5&pit=5&_res_tag_=audio"
}
```

## 发言人列表

`speakersJson` 用来给多角色朗读和角色卡配音提供可选发言人。可以平铺:

```json
[
  {
    "speakerName": "晓晓",
    "toneID": "zh-CN-XiaoxiaoNeural"
  },
  {
    "speakerName": "云希",
    "toneID": "zh-CN-YunxiNeural"
  }
]
```

也可以分组:

```json
[
  {
    "groupId": "female",
    "groupName": "女声",
    "items": [
      {
        "speakerName": "晓晓",
        "toneID": "zh-CN-XiaoxiaoNeural"
      }
    ]
  },
  {
    "groupId": "male",
    "groupName": "男声",
    "items": [
      {
        "speakerName": "云希",
        "toneID": "zh-CN-YunxiNeural"
      }
    ]
  }
]
```

规则中可以直接使用 `currentToneID`:

```json
{
  "method": "POST",
  "body": {
    "text": "{{speakText}}",
    "voice": "{{currentToneID || 'zh-CN-XiaoxiaoNeural'}}"
  }
}
```

## 情绪列表

`emotionsJson` 用来给发言人附加情绪标志。可以平铺或分组:

```json
[
  {
    "emotionName": "高兴",
    "emotionTag": "[高兴]"
  },
  {
    "emotionName": "悲伤",
    "emotionTag": "[悲伤]"
  }
]
```

规则中可以把情绪拼进朗读文本:

```json
{
  "method": "POST",
  "body": {
    "text": "{{currentEmotionTag || ''}}{{speakText}}",
    "voice": "{{currentToneID}}"
  }
}
```

不填写 `speakersJson` 时，该 HTTP TTS 仍会作为一个普通发言人出现在角色配音选择里。
