package com.example.demo.mapper;

import com.example.demo.model.ChatMessageHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMessageHistoryMapper {

    List<ChatMessageHistory> selectByChatId(@Param("chatId") String chatId);

    int deleteByChatId(@Param("chatId") String chatId);

    int insertBatch(@Param("list") List<ChatMessageHistory> list);
}

