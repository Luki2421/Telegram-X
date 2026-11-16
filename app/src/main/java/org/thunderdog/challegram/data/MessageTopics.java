/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.data;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;

import tgx.td.Td;

/** Topic identity for navigation. A null topic means the whole chat, not a wildcard. */
@me.vkryl.annotation.MadeByLLM(value = "gpt-6-astra", id = "forum-topics", ref = "PR #1095")
public final class MessageTopics {
  private MessageTopics () {
  }

  @me.vkryl.annotation.MadeByLLM(value = "gpt-6", id = "01a1e663-9e2d-7733-a6ce-2373c7eceaa5")
  public static boolean canMergeMessages (
    @Nullable TdApi.MessageTopic topicId,
    @Nullable TdApi.MessageTopic otherTopicId
  ) {
    // Reply threads share the regular chat timeline; they aren't separate forum topics.
    if (topicId instanceof TdApi.MessageTopicThread) {
      topicId = null;
    }
    if (otherTopicId instanceof TdApi.MessageTopicThread) {
      otherTopicId = null;
    }
    return Td.equalsTo(topicId, otherTopicId);
  }

  @Nullable
  public static TdApi.MessageTopic effectiveTopic (
    @Nullable ThreadInfo threadInfo,
    @Nullable TdApi.MessageTopic topicId
  ) {
    return threadInfo != null ? threadInfo.getMessageTopicId() : topicId;
  }

  public static boolean sameChat (
    long chatId,
    @Nullable TdApi.MessageTopic topicId,
    long otherChatId,
    @Nullable TdApi.MessageTopic otherTopicId
  ) {
    return chatId == otherChatId && Td.equalsTo(topicId, otherTopicId);
  }

  public static boolean sameChat (
    long chatId,
    @Nullable TdApi.MessageTopic topicId,
    boolean scheduled,
    long otherChatId,
    @Nullable TdApi.MessageTopic otherTopicId,
    boolean otherScheduled
  ) {
    return scheduled == otherScheduled && sameChat(chatId, topicId, otherChatId, otherTopicId);
  }
}
