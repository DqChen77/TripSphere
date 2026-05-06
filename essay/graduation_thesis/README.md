# TripSphere Graduation Thesis

本目录是毕业论文初稿 LaTeX 工程，正文聚焦 TripSphere 中面向 LangGraph 智能体的 AI 原生应用层故障注入机制。

## 依赖安装

```bash
sudo apt update
sudo apt install -y texlive-xetex texlive-latex-recommended texlive-latex-extra texlive-lang-chinese texlive-fonts-recommended latexmk biber
```

如果需要完整 TeX Live，可以使用：

```bash
sudo apt install texlive-full
```

## 编译

```bash
latexmk -xelatex main.tex
```

## 说明

- 已实践内容按仓库文档和当前实现描述。
- 未完成实验结果、指标数值和图表均保留为 `待补充`，不填造数据。
- 如后续拿到学校官方模板，可将 `chapters/` 内正文迁移过去。
