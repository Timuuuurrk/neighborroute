
package com.neighborroute.telegrambot.bot;

import com.neighborroute.telegrambot.user.TelegramUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import com.neighborroute.telegrambot.client.OrderServiceClient;
import com.neighborroute.telegrambot.order.OrderDraftService;
import com.neighborroute.telegrambot.order.OrderDraftService.Draft;
import com.neighborroute.telegrambot.client.BatchServiceClient;
import com.neighborroute.telegrambot.order.OrderHistoryService;
import com.neighborroute.telegrambot.order.OrderHistoryService.OrderEntry;

import org.springframework.web.client.RestClientException;

import java.util.List;

import java.math.BigDecimal;
import java.util.UUID;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import com.neighborroute.telegrambot.user.TelegramUserService.Address;

import java.util.List;
import java.util.UUID;

@Component
public class NeighborRouteBot
        implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(NeighborRouteBot.class);

    private final TelegramClient telegramClient;

    private final TelegramUserService userService;

    private final OrderDraftService orderDraftService;
    private final OrderServiceClient orderServiceClient;

    private final BatchServiceClient batchServiceClient;

    private final OrderHistoryService orderHistoryService;


    public NeighborRouteBot(
            @Value("${telegram.bot.token}") String token,
            TelegramUserService userService,
            OrderDraftService orderDraftService,
            OrderServiceClient orderServiceClient,
            BatchServiceClient batchServiceClient,
            OrderHistoryService orderHistoryService
    ) {
        this.telegramClient = new OkHttpTelegramClient(token);
        this.userService = userService;
        this.orderDraftService = orderDraftService;
        this.orderServiceClient = orderServiceClient;
        this.batchServiceClient = batchServiceClient;
        this.orderHistoryService = orderHistoryService;
    }





    @Override
    public void consume(Update update) {
        if (!update.hasMessage()
                || !update.getMessage().hasText()) {
            return;
        }

        if (!"private".equals(
                update.getMessage().getChat().getType()
        )) {
            return;
        }

        if (update.getMessage().getFrom() == null) {
            return;
        }

        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        String text = update.getMessage().getText().trim();

        userService.registerIfAbsent(userId);

        String state = userService.getDialogState(userId);

        if ("JOIN_PENDING".equals(state)) {
            if ("/resume".equals(text)) {
                joinPreparedOrder(chatId, userId);
            } else {
                sendText(chatId,
                        "Заказ уже подтверждён.\n"
                                + "Для присоединения к группе "
                                + "отправьте /resume");
            }
            return;
        }

        if ("JOINING_BATCH".equals(state)) {
            sendText(chatId,
                    "Присоединение к группе начато. "
                            + "Повторный запрос не отправляется. "
                            + "Если операция зависла, потребуется "
                            + "проверка её результата.");
            return;
        }

        if ("JOIN_REVIEW_REQUIRED".equals(state)) {
            sendText(chatId,
                    "Результат присоединения требует проверки.\n"
                            + "Заказ сохранён. Не создавайте "
                            + "его повторно.");
            return;
        }


        if ("CREATING_ORDER".equals(state)) {
            sendText(chatId,
                    "Создание заказа требует проверки. "
                            + "Повторный запрос не отправлен, "
                            + "чтобы случайно не создать дубликат.");
            return;
        }

        if ("CONFIRMING_ORDER".equals(state)
                && !"✅ Подтвердить заказ".equals(text)) {
            sendText(chatId,
                    "Заказ уже создан. Нажмите "
                            + "«✅ Подтвердить заказ», "
                            + "чтобы завершить подтверждение.");
            return;
        }


        if (text.equals("/cancel_order")
                || text.startsWith("/cancel_order ")) {

            if (!"IDLE".equals(state)) {
                sendText(chatId,
                        "Сначала завершите текущее действие "
                                + "или отмените ввод черновика через /cancel.");
                return;
            }

            handleCancelOrderCommand(chatId, userId, text);
            return;
        }


        switch (text) {
            case "/start", "/menu" -> {
                orderDraftService.cancelDraft(userId);
                userService.cancelAddressInput(userId);
                sendMainMenu(chatId);
            }

            case "/cancel", "❌ Отменить заказ" -> {
                orderDraftService.cancelDraft(userId);
                userService.cancelAddressInput(userId);
                sendText(chatId, "Действие отменено.");
                sendMainMenu(chatId);
            }

            case "🛒 Новый заказ" -> {
                userService.cancelAddressInput(userId);

                var address = userService.getAddress(userId);

                if (address.building() == null) {
                    sendText(chatId,
                            "Сначала укажите адрес через "
                                    + "кнопку «📍 Мой адрес».");
                    return;
                }

                orderDraftService.begin(userId);

                sendText(chatId,
                        "Введите название товара.\n"
                                + "Например: Молоко\n\n"
                                + "/cancel — отмена");
            }

            case "✅ Подтвердить заказ" ->
                    submitOrder(chatId, userId);

            case "📍 Мой адрес" -> {
                orderDraftService.cancelDraft(userId);

                var address = userService.getAddress(userId);
                userService.beginAddressInput(userId);

                if (address.building() == null) {
                    sendText(chatId,
                            "Укажите улицу и номер дома.\n"
                                    + "Например: ул. Ленина, 10");
                } else {
                    sendText(chatId,
                            "Текущий дом: " + address.building()
                                    + "\nВведите новый адрес дома.");
                }
            }


            case "📦 Мои заказы" -> {
                orderDraftService.cancelDraft(userId);
                userService.cancelAddressInput(userId);

                showMyOrders(chatId, userId);
            }

            case "🏠 Группы дома" -> {
                orderDraftService.cancelDraft(userId);
                userService.cancelAddressInput(userId);

                sendText(chatId,
                        "Просмотр групп дома добавим следующим.");
            }


            default -> {
                if (state.startsWith("AWAITING_")
                        && !state.equals("AWAITING_BUILDING")
                        && !state.equals("AWAITING_DETAILS")) {
                    handleOrderInput(chatId, userId, state, text);
                } else {
                    handleAddressInput(chatId, userId, text);
                }
            }
        }
    }



    private void handleAddressInput(
            long chatId,
            long telegramUserId,
            String text
    ) {
        String state = userService.getDialogState(telegramUserId);

        switch (state) {
            case "AWAITING_BUILDING" -> {
                String building = text.replaceAll("\\s+", " ");

                if (building.isBlank() || building.length() > 250) {
                    sendText(
                            chatId,
                            "Адрес дома должен содержать от 1 до 250 символов."
                    );
                    return;
                }

                userService.saveDraftBuilding(
                        telegramUserId, building
                );

                sendText(
                        chatId,
                        "Дом: " + building
                                + "\n\nТеперь укажите подъезд и квартиру."
                                + "\nНапример: подъезд 2, квартира 45"
                                + "\nЕсли деталей нет, отправьте -"
                                + "\n\n/cancel — отмена"
                );
            }

            case "AWAITING_DETAILS" -> {
                if (text.isBlank() || text.length() > 250) {
                    sendText(
                            chatId,
                            "Детали доставки должны содержать "
                                    + "от 1 до 250 символов."
                    );
                    return;
                }

                String details = text.equals("-") ? null : text;

                Address address = userService.completeAddressInput(
                        telegramUserId, details
                );

                sendText(
                        chatId,
                        "Адрес успешно сохранён!\n\n"
                                + "Дом: " + address.building()
                                + "\nДетали: "
                                + (address.details() == null
                                ? "не указаны"
                                : address.details())
                );

                sendMainMenu(chatId);
            }

            default -> sendText(
                    chatId,
                    "Выберите действие в меню или отправьте /menu."
            );
        }
    }


    private void submitOrder(long chatId, long userId) {
        String state = userService.getDialogState(userId);

        if (!"AWAITING_CONFIRMATION".equals(state)
                && !"CONFIRMING_ORDER".equals(state)) {
            sendText(chatId, "Нет заказа для подтверждения.");
            return;
        }

        Draft draft = orderDraftService.getDraft(userId);
        UUID orderId = draft.createdOrderId();

        if ("AWAITING_CONFIRMATION".equals(state)) {
            if (!orderDraftService.claimCreation(userId)) {
                sendText(chatId,
                        "Заказ уже обрабатывается.");
                return;
            }

            try {
                orderId = orderServiceClient.createOrder(
                        draft.customerId(),
                        draft.building(),
                        draft.details(),
                        draft.productName(),
                        draft.quantity(),
                        draft.unitPrice()
                );

                orderDraftService.rememberCreatedOrder(
                        userId, orderId
                );
            } catch (Exception e) {
                log.error("Order creation failed", e);

                sendText(chatId,
                        "Не удалось получить подтверждение "
                                + "создания заказа. Повторная отправка "
                                + "заблокирована во избежание дубликата. "
                                + "Проверьте логи и Order Service.");
                return;
            }
        }

        if (orderId == null) {
            log.error("Missing created order ID for user {}", userId);
            sendText(chatId, "Не найден ID созданного заказа.");
            return;
        }

        try {
            try {
                orderServiceClient.confirmOrder(orderId);
            } catch (Exception confirmationError) {
                String actualStatus =
                        orderServiceClient.getOrderStatus(orderId);

                if (!"CONFIRMED".equals(actualStatus)
                        && !"DELIVERED".equals(actualStatus)) {
                    throw confirmationError;
                }
            }

            orderDraftService.prepareBatchJoin(userId, orderId);
            joinPreparedOrder(chatId, userId);
        } catch (Exception e) {
            log.error("Order confirmation failed: {}", orderId, e);

            sendText(chatId,
                    "Заказ создан, но его подтверждение "
                            + "не завершено.\n\nID: " + orderId
                            + "\nДля повторной проверки отправьте:"
                            + "\n✅ Подтвердить заказ");
        }
    }




    private void sendMainMenu(long chatId) {
        KeyboardRow firstRow = new KeyboardRow();
        firstRow.add("🛒 Новый заказ");
        firstRow.add("📦 Мои заказы");

        KeyboardRow secondRow = new KeyboardRow();
        secondRow.add("🏠 Группы дома");
        secondRow.add("📍 Мой адрес");

        ReplyKeyboardMarkup keyboard =
                ReplyKeyboardMarkup.builder()
                        .keyboard(List.of(firstRow, secondRow))
                        .resizeKeyboard(true)
                        .oneTimeKeyboard(false)
                        .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Добро пожаловать в NeighborRoute!\nВыберите действие:")
                .replyMarkup(keyboard)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send main menu", e);
        }
    }


    private void joinPreparedOrder(long chatId, long userId) {
        Draft draft = orderDraftService.getDraft(userId);
        UUID orderId = draft.createdOrderId();

        if (orderId == null) {
            sendText(chatId, "Не найден ID созданного заказа.");
            return;
        }

        if (!orderDraftService.claimBatchJoin(userId, orderId)) {
            sendText(chatId,
                    "Присоединение уже обрабатывается.");
            return;
        }

        try {
            UUID batchId =
                    batchServiceClient.joinBatch(orderId);

            orderDraftService.completeBatchJoin(
                    userId, orderId, batchId
            );

            sendText(chatId,
                    "Заказ успешно добавлен "
                            + "в совместную доставку!\n\n"
                            + "Заказ: " + orderId
                            + "\nГруппа: " + batchId);

            sendMainMenu(chatId);

        } catch (Exception e) {
            log.error("Failed to join batch for order {}", orderId, e);

            try {
                orderDraftService.markBatchJoinUncertain(
                        userId, orderId
                );
            } catch (Exception storageError) {
                log.error(
                        "Failed to persist uncertain join state",
                        storageError
                );
            }

            sendText(chatId,
                    "Заказ подтверждён, но присоединение "
                            + "к группе требует проверки.\n\n"
                            + "ID заказа: " + orderId
                            + "\nПовторный запрос автоматически "
                            + "не отправляется.");
        }
    }



    private void handleOrderInput(
            long chatId,
            long userId,
            String state,
            String text
    ) {
        switch (state) {
            case "AWAITING_PRODUCT" -> {
                String product = text.replaceAll("\\s+", " ");

                if (product.isBlank() || product.length() > 250) {
                    sendText(chatId,
                            "Название товара должно содержать "
                                    + "от 1 до 250 символов.");
                    return;
                }

                orderDraftService.saveProduct(userId, product);
                sendText(chatId,
                        "Товар: " + product
                                + "\nВведите количество от 1 до 1000.");
            }

            case "AWAITING_QUANTITY" -> {
                int quantity;

                try {
                    quantity = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    sendText(chatId, "Введите целое количество.");
                    return;
                }

                if (quantity < 1 || quantity > 1000) {
                    sendText(chatId,
                            "Количество должно быть от 1 до 1000.");
                    return;
                }

                orderDraftService.saveQuantity(userId, quantity);

                sendText(chatId,
                        "Введите цену за одну единицу товара.\n"
                                + "Например: 120.50");
            }

            case "AWAITING_PRICE" -> {
                String normalized = text.replace(',', '.');

                if (!normalized.matches("\\d+(\\.\\d{1,2})?")) {
                    sendText(chatId,
                            "Введите положительную цену "
                                    + "с точностью до двух знаков.");
                    return;
                }

                BigDecimal price = new BigDecimal(normalized);

                if (price.compareTo(BigDecimal.ZERO) <= 0
                        || price.compareTo(
                        new BigDecimal("9999999999.99")) > 0) {
                    sendText(chatId, "Некорректная цена.");
                    return;
                }

                orderDraftService.savePrice(userId, price);

                Draft draft = orderDraftService.getDraft(userId);

                BigDecimal total = draft.unitPrice().multiply(
                        BigDecimal.valueOf(draft.quantity())
                );

                sendText(chatId,
                        "Проверьте заказ:\n\n"
                                + "Товар: " + draft.productName()
                                + "\nКоличество: " + draft.quantity()
                                + "\nЦена: " + draft.unitPrice()
                                + "\nИтого: " + total
                                + "\nДом: " + draft.building()
                                + "\n\nДля создания отправьте:"
                                + "\n✅ Подтвердить заказ"
                                + "\n\nДля отмены:"
                                + "\n❌ Отменить заказ");
            }

            case "AWAITING_CONFIRMATION" ->
                    sendText(chatId,
                            "Подтвердите заказ или отмените его.");

            default ->
                    sendText(chatId, "Выберите действие через /menu.");
        }
    }


    private void sendText(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram message", e);
        }
    }



    private void showMyOrders(long chatId, long userId) {
        List<OrderEntry> orders =
                orderHistoryService.findRecentOrders(userId);

        if (orders.isEmpty()) {
            sendText(chatId,
                    "У вас пока нет заказов.\n"
                            + "Нажмите «🛒 Новый заказ», "
                            + "чтобы создать первый.");
            return;
        }

        StringBuilder message =
                new StringBuilder("📦 Ваши последние заказы:\n");

        int number = 1;

        for (OrderEntry order : orders) {
            String status;

            try {
                status = orderServiceClient.getOrderStatus(
                        order.orderId()
                );
            } catch (RestClientException e) {
                log.warn(
                        "Failed to get status for order {}",
                        order.orderId(),
                        e
                );

                status = "UNAVAILABLE";
            }

            message.append("\n")
                    .append(number++)
                    .append(". Заказ: ")
                    .append(order.orderId())
                    .append("\nСтатус: ")
                    .append(formatOrderStatus(status))
                    .append("\n");

            if (order.batchId() != null) {
                message.append("Группа: ")
                        .append(order.batchId())
                        .append("\n");
            }

            if (!"JOINED".equals(order.joinStatus())) {
                message.append("Присоединение: ")
                        .append(order.joinStatus())
                        .append("\n");
            }
        }

        message.append(
                "\nЧтобы отменить подтверждённый заказ, "
                        + "отправьте:\n"
                        + "/cancel_order UUID"
        );

        sendText(chatId, message.toString());
    }

    private String formatOrderStatus(String status) {
        return switch (status) {
            case "CREATED" -> "📝 Создан";
            case "CONFIRMED" -> "✅ Подтверждён";
            case "CANCEL_PENDING" -> "⏳ Отмена выполняется";
            case "CANCELLED" -> "❌ Отменён";
            case "DELIVERED" -> "📦 Доставлен";
            case "UNAVAILABLE" -> "⚠️ Временно недоступен";
            default -> status;
        };
    }


    private void handleCancelOrderCommand(
            long chatId,
            long userId,
            String text
    ) {
        String[] parts = text.split("\\s+");

        if (parts.length != 2) {
            sendText(chatId,
                    "Укажите UUID заказа.\n\n"
                            + "Пример:\n"
                            + "/cancel_order 0082c5f9-b7a0-4c6d-a5cc-8ab1cea98db4");
            return;
        }

        UUID orderId;

        try {
            orderId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            sendText(chatId, "Некорректный UUID заказа.");
            return;
        }

        if (!orderHistoryService.ownsOrder(userId, orderId)) {
            sendText(chatId,
                    "Заказ не найден среди ваших заказов.");
            return;
        }

        String status;

        try {
            status = orderServiceClient.getOrderStatus(orderId);
        } catch (RestClientException e) {
            log.warn("Failed to fetch order {}", orderId, e);
            sendText(chatId,
                    "Не удалось проверить состояние заказа. "
                            + "Попробуйте посмотреть его статус позже.");
            return;
        }

        if ("CANCELLED".equals(status)) {
            sendText(chatId, "Этот заказ уже отменён.");
            return;
        }

        if ("DELIVERED".equals(status)) {
            sendText(chatId,
                    "Заказ уже доставлен. Отменить его нельзя.");
            return;
        }

        if ("CANCEL_PENDING".equals(status)) {
            sendText(chatId,
                    "Отмена заказа уже выполняется.");
            return;
        }

        if (!"CONFIRMED".equals(status)) {
            sendText(chatId,
                    "Заказ сейчас нельзя отменить. "
                            + "Его статус: " + formatOrderStatus(status));
            return;
        }

        try {
            orderServiceClient.cancelOrder(orderId);
        } catch (HttpClientErrorException.Conflict e) {
            log.info("Cancellation rejected for order {}", orderId);

            sendText(chatId,
                    "Не удалось отменить заказ: "
                            + "операция конфликтует с его текущим "
                            + "состоянием или состоянием группы.\n"
                            + "Обновите список через «📦 Мои заказы».");
            return;
        } catch (RestClientException e) {
            log.error("Cancellation result unknown: {}", orderId, e);

            sendText(chatId,
                    "Не удалось получить подтверждение отмены.\n"
                            + "Результат запроса неизвестен.\n"
                            + "Проверьте актуальный статус через "
                            + "«📦 Мои заказы».");
            return;
        }

        try {
            String actualStatus =
                    orderServiceClient.getOrderStatus(orderId);

            switch (actualStatus) {
                case "CANCELLED" ->
                        sendText(chatId,
                                "✅ Заказ успешно отменён!\n\n"
                                        + "ID: " + orderId);

                case "CANCEL_PENDING" ->
                        sendText(chatId,
                                "⏳ Запрос отмены принят.\n"
                                        + "Отмена ещё выполняется.\n\n"
                                        + "ID: " + orderId);

                default ->
                        sendText(chatId,
                                "Запрос отмены обработан.\n"
                                        + "Текущий статус: "
                                        + formatOrderStatus(actualStatus));
            }

        } catch (RestClientException e) {
            log.warn(
                    "Cancellation sent but status unavailable: {}",
                    orderId,
                    e
            );

            sendText(chatId,
                    "Запрос отмены принят, но не удалось "
                            + "проверить итоговый статус.\n"
                            + "Откройте «📦 Мои заказы» позже.");
        }
    }



}
