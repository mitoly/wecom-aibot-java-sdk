package com.wecom.aibot.example;

import com.wecom.aibot.model.*;

import java.util.*;

/** 五类模板卡片构造示例；字段通过 SDK 模型设置，不手工拼接 JSON。 */
public final class ExampleCards {
    private static final String DOC_URL = "https://developer.work.weixin.qq.com/document/path/101463";
    private static final String IMAGE_URL = "https://res.mail.qq.com/node/ww/wwopenmng/images/independent/doc/test_pic_msg1.png";

    private ExampleCards() {
    }

    /** 构造指定类型的卡片，taskId 在一次交互的发送与更新中保持一致。 */
    public static TemplateCard create(String cardType, String taskId) {
        TemplateCard card = new TemplateCard();
        card.setCardType(cardType);
        card.setTaskId(taskId);
        card.setMainTitle(new CardTitle("Java SDK 演示", "点击按钮或提交选项，观察事件与更新卡片"));

        CardSource source = new CardSource();
        source.setDesc("SDK 示例");
        source.setDescColor(1);
        card.setSource(source);

        switch (cardType) {
            case "text_notice":
                card.setSubTitleText("文本通知卡片：用于展示消息摘要和跳转链接。");
                card.setHorizontalList(Collections.singletonList(new CardKV("版本", "1.1.0-SNAPSHOT")));
                addNavigation(card);
                addMenu(card);
                break;
            case "news_notice":
                CardImage image = new CardImage();
                image.setUrl(IMAGE_URL);
                image.setAspectRatio(1.5);
                card.setCardImage(image);
                card.setVerticalContentList(Collections.singletonList(new CardTitle("图文展示", "示例图片可替换为自己的公开图片地址")));
                addNavigation(card);
                addMenu(card);
                break;
            case "button_interaction":
                card.setButtonSelection(selection("environment", "选择环境"));
                card.setButtonList(Arrays.asList(
                        new CardButton("确认", 1, "confirm"),
                        new CardButton("取消", 2, "cancel")));
                addMenu(card);
                break;
            case "vote_interaction":
                CardCheckbox checkbox = new CardCheckbox();
                checkbox.setQuestionKey("preferred_feature");
                checkbox.setMode(1);
                checkbox.setOptionList(Arrays.asList(option("stream", "流式回复"), option("media", "媒体文件")));
                card.setCheckbox(checkbox);
                card.setSubmitButton(submit("提交选择", "submit_vote"));
                break;
            case "multiple_interaction":
                card.setSelectList(Arrays.asList(
                        selection("environment", "运行环境"),
                        selection("target", "测试目标")));
                card.setSubmitButton(submit("提交选项", "submit_multiple"));
                break;
            default:
                throw new IllegalArgumentException("卡片类型应为 text_notice/news_notice/button_interaction/vote_interaction/multiple_interaction");
        }

        ReplyFeedback feedback = new ReplyFeedback();
        feedback.setId(taskId);
        card.setFeedback(feedback);
        return card;
    }

    /** 点击后的更新示例；保留原 taskId，并将交互输入设为不可选。 */
    public static TemplateCard processed(String cardType, String taskId, String result) {
        return processed(cardType, taskId, result, null);
    }

    /** 更新时保留实际提交的选择，避免禁用后显示默认选项。 */
    public static TemplateCard processed(String cardType, String taskId, String result, SelectedItems submitted) {
        TemplateCard card = create(cardType, taskId);
        Map<String, List<String>> selected = new HashMap<>();
        if (submitted != null && submitted.getSelectedItem() != null) {
            for (SelectedItem item : submitted.getSelectedItem()) {
                if (item.getOptionIds() != null && item.getOptionIds().getOptionId() != null) {
                    selected.put(item.getQuestionKey(), item.getOptionIds().getOptionId());
                }
            }
        }
        card.setMainTitle(new CardTitle("已收到交互", result));
        if (card.getButtonSelection() != null) {
            preserveSelection(card.getButtonSelection(), selected);
            card.getButtonSelection().setDisable(true);
        }
        if (card.getCheckbox() != null) {
            CardCheckbox checkbox = card.getCheckbox();
            List<String> ids = selected.getOrDefault(checkbox.getQuestionKey(), Collections.emptyList());
            for (CardOption option : checkbox.getOptionList()) {
                option.setIsChecked(ids.contains(option.getId()));
            }
            checkbox.setDisable(true);
        }
        if (card.getSelectList() != null) {
            for (CardSelectionItem selection : card.getSelectList()) {
                preserveSelection(selection, selected);
                selection.setDisable(true);
            }
        }
        if (card.getButtonList() != null) {
            card.setButtonList(Collections.singletonList(new CardButton("已处理", 1, "processed")));
        }
        return card;
    }

    private static void preserveSelection(CardSelectionItem selection, Map<String, List<String>> selected) {
        List<String> ids = selected.getOrDefault(selection.getQuestionKey(), Collections.emptyList());
        for (CardOption option : selection.getOptionList()) {
            if (ids.contains(option.getId())) {
                selection.setSelectedId(option.getId());
                break;
            }
        }
    }

    private static void addNavigation(TemplateCard card) {
        CardAction action = new CardAction();
        action.setType(1);
        action.setUrl(DOC_URL);
        card.setCardAction(action);

        CardJumpAction jump = new CardJumpAction();
        jump.setType(1);
        jump.setTitle("查看开发文档");
        jump.setUrl(DOC_URL);
        card.setJumpList(Collections.singletonList(jump));
    }

    private static void addMenu(TemplateCard card) {
        CardActionMenu menu = new CardActionMenu();
        menu.setDesc("演示操作");
        menu.setActionList(Arrays.asList(new CardButton("标为已读", "mark_read"), new CardButton("忽略", "ignore")));
        card.setActionMenu(menu);
    }

    private static CardSelectionItem selection(String key, String title) {
        CardSelectionItem selection = new CardSelectionItem();
        selection.setQuestionKey(key);
        selection.setTitle(title);
        selection.setSelectedId("test");
        selection.setOptionList(Arrays.asList(option("test", "测试"), option("demo", "演示")));
        return selection;
    }

    private static CardOption option(String id, String text) {
        CardOption option = new CardOption();
        option.setId(id);
        option.setText(text);
        return option;
    }

    private static CardSubmitButton submit(String text, String key) {
        CardSubmitButton button = new CardSubmitButton();
        button.setText(text);
        button.setKey(key);
        return button;
    }
}
