# WWheel 转盘 App

一个本地桌面转盘应用，使用 Java Swing 实现，无需额外依赖。

## 功能

- 显示转盘和选项名字
- 真权重影响扇区大小和选中概率
- 假权重可填写；不填写时显示为真权重，不参与抽选
- 经过选项播放 tick 音效，选中后播放选中音效
- 选中后调用系统 TTS 播放结果
- 可调旋转时长、配色、字体大小
- 转盘列表、搜索；标题匹配权重高于选项匹配
- 分组和嵌套子分组
- PWH 导入
- WWD 完整导入/导出
- PWH 兼容导出（会舍弃分组、设置、假权重等 PWH 不支持的功能）

## 构建

```bash
./build.sh
```

## 运行

```bash
java -jar build/wwheel.jar
```

数据默认保存到：

```text
~/.local/share/wwheel/library.wwd.json
```

## 选项输入格式

新建/编辑转盘时，每行一个选项：

```text
名字,真权重,假权重
```

假权重可以留空：

```text
选项 A,1,
选项 B,3,10
```

## PWH 格式

PWH 文件格式为：

```text
ASCII "pwh" + gzip(json)
```

导入时支持形如：

```json
{
  "exportTime": 1783770433840,
  "version": 5,
  "wheels": [
    {
      "dbId": 1,
      "items": [
        {"text": "1"},
        {"text": "2", "weight": 12}
      ],
      "title": "1"
    }
  ]
}
```
