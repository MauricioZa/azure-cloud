// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.chtrembl.petstoreassistant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.chtrembl.petstoreassistant.model.AzurePetStoreSessionInfo;
import com.chtrembl.petstoreassistant.model.DPResponse;
import com.chtrembl.petstoreassistant.service.AzureAIServices.Classification;
import com.chtrembl.petstoreassistant.service.IAzureAIServices;
import com.chtrembl.petstoreassistant.service.IAzurePetStore;
import com.chtrembl.petstoreassistant.utility.PetStoreAssistantUtilities;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.bot.builder.ActivityHandler;
import com.microsoft.bot.builder.MessageFactory;
import com.microsoft.bot.builder.TurnContext;
import com.microsoft.bot.builder.UserState;
import com.microsoft.bot.schema.Attachment;
import com.microsoft.bot.schema.ChannelAccount;

/**
 * This class implements the functionality of the Bot.
 *
 * <p>
 * This is where application specific logic for interacting with the users would
 * be added. For this
 * sample, the {@link #onMessageActivity(TurnContext)} echos the text back to
 * the user. The {@link
 * #onMembersAdded(List, TurnContext)} will send a greeting to new conversation
 * participants.
 * </p>
 */
@Component
@Primary
public class PetStoreAssistantBot extends ActivityHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PetStoreAssistantBot.class);

    //clean this up
    private Map<String, AzurePetStoreSessionInfo> sessionCache = new HashMap<String, AzurePetStoreSessionInfo>();

    @Autowired
    private IAzureAIServices azureOpenAI;

    @Autowired
    private IAzurePetStore azurePetStore;

    private String WELCOME_MESSAGE = "Hello and welcome to the Azure Pet Store, you can ask me questions about our products, your shopping cart and your order, you can also ask me for information about pet animals. How can I help you?";

    private UserState userState;

    public PetStoreAssistantBot(UserState withUserState) {
        this.userState = withUserState;
    }

    // onTurn processing isn't working with DP, not being used...
    @Override
    public CompletableFuture<Void> onTurn(TurnContext turnContext) {
        return super.onTurn(turnContext)
                .thenCompose(saveResult -> userState.saveChanges(turnContext));
    }

    @Override
    protected CompletableFuture<Void> onMessageActivity(TurnContext turnContext) {
        String text = turnContext.getActivity().getText().toLowerCase().trim();
        
        if(isErroneousRequest(text))
        {
            return null;
        }

        AzurePetStoreSessionInfo azurePetStoreSessionInfo = configureSession(turnContext, text);

        // get the text without the session id and csrf token
        if(azurePetStoreSessionInfo != null && azurePetStoreSessionInfo.getNewText() != null)
        {
            text = azurePetStoreSessionInfo.getNewText();
        }
        
        //the client kickoff message
        if(text.equals("..."))
        {
            return turnContext.sendActivity(
            MessageFactory.text(WELCOME_MESSAGE)).thenApply(sendResult -> null);
        }

         //DEBUG ONLY
        if (text.contains("debug"))
        {      
            return turnContext.sendActivity(
                MessageFactory.text("id:"+azurePetStoreSessionInfo.getId())).thenApply(sendResult -> null);
        }

        if (text.contains("card")) {
            if(azurePetStoreSessionInfo != null && azurePetStoreSessionInfo.getNewText() != null)
            { 
            text = azurePetStoreSessionInfo.getNewText();
            }
            String jsonString = "{\"type\":\"buttonWithImage\",\"id\":\"buttonWithImage\",\"data\":{\"title\":\"Soul Machines\",\"imageUrl\":\"https://www.soulmachines.com/wp-content/uploads/cropped-sm-favicon-180x180.png\",\"description\":\"Soul Machines is the leader in astonishing AGI\",\"imageAltText\":\"some text\",\"buttonText\":\"push me\"}}";

            Attachment attachment = new Attachment();
            attachment.setContentType("application/json");

            attachment.setContent(new Gson().fromJson(jsonString, JsonObject.class));
            attachment.setName("public-content-card");

            return turnContext.sendActivity(
                    MessageFactory.attachment(attachment, "I have something nice to show @showcards(content-card) you."))
                    .thenApply(sendResult -> null);
        }

        if (text.contains("ball"))
        {
                String jsonString = "{\"type\":\"image\",\"id\":\"image-ball\",\"data\":{\"url\": \"https://raw.githubusercontent.com/chtrembl/staticcontent/master/dog-toys/ball.jpg?raw=true\",\"alt\": \"This is a ball\"}}";
                Attachment attachment = new Attachment();
                attachment.setContentType("application/json");
    
                attachment.setContent(new Gson().fromJson(jsonString, JsonObject.class));
                attachment.setName("public-image-ball");
    
                return turnContext.sendActivity(
                        MessageFactory.attachment(attachment, "I have something nice to show @showcards(image-ball) you."))
                        .thenApply(sendResult -> null);
        }
        //END DEBUG

        DPResponse dpResponse = this.azureOpenAI.classification(text);

        if (dpResponse.getClassification() == null) {
            dpResponse.setClassification(Classification.SEARCH_FOR_PRODUCTS);
            dpResponse = this.azureOpenAI.search(text, dpResponse.getClassification());
        }

        switch (dpResponse.getClassification()) {
            case UPDATE_SHOPPING_CART:
                if (azurePetStoreSessionInfo != null) {
                    dpResponse = this.azureOpenAI.search(text, Classification.SEARCH_FOR_PRODUCTS);
                    if (dpResponse.getProducts() != null) {
                        dpResponse = this.azurePetStore.updateCart(azurePetStoreSessionInfo,
                                dpResponse.getProducts().get(0).getProductId());
                    }
                }
                else
                {
                    dpResponse.setDpResponseText("update shopping cart request without session... text: "+text);
                }
                break;
            case VIEW_SHOPPING_CART:
                if (azurePetStoreSessionInfo != null) {
                    dpResponse = this.azurePetStore.viewCart(azurePetStoreSessionInfo);
                }
                else
                {
                    dpResponse.setDpResponseText("view shopping cart request without session... text: "+text);
                }
                break;
            case PLACE_ORDER:
                if (azurePetStoreSessionInfo != null) {
                    dpResponse = this.azurePetStore.completeCart(azurePetStoreSessionInfo);
                }
                else
                {
                    dpResponse.setDpResponseText("place order request without session... text: "+text);
                }
                break;
            case SEARCH_FOR_DOG_FOOD:
            case SEARCH_FOR_DOG_TOYS:
            case SEARCH_FOR_CAT_FOOD:
            case SEARCH_FOR_CAT_TOYS:
            case SEARCH_FOR_FISH_FOOD:
            case SEARCH_FOR_FISH_TOYS:
            case MORE_PRODUCT_INFORMATION:
            case SEARCH_FOR_PRODUCTS:
                if (azurePetStoreSessionInfo == null) {
                    dpResponse.setDpResponseText("search for products request without session... text: "+text);
                }
                else
                {
                    dpResponse = this.azureOpenAI.search(text, dpResponse.getClassification());
                }
                break;
            case SOMETHING_ELSE:
                if (azurePetStoreSessionInfo == null) {
                    dpResponse.setDpResponseText("chatgpt request without session... text: "+text);
                }
                else
                {
                    if(!text.isEmpty())
                    {
                        dpResponse = this.azureOpenAI.completion(text, dpResponse.getClassification());
                    }
                    else
                    {
                        dpResponse.setDpResponseText("chatgpt called without a search query... text: "+text);
                    }
                }
                break;
        }

        if((dpResponse.getDpResponseText() == null))
        {
            String responseText = "I am not sure how to handle that.";

            if((azurePetStoreSessionInfo == null))
            {
                responseText += " It may be because I did not have your session information.";
            }
            dpResponse.setDpResponseText(responseText);
        }

        return turnContext.sendActivity(
                MessageFactory.text(dpResponse.getDpResponseText())).thenApply(sendResult -> null);
       }

    // this method only gets invoked once, regardless of browser/user, state isnt working right for some reason (DP related, not in issue with emulator)
    @Override
    protected CompletableFuture<Void> onMembersAdded(
            List<ChannelAccount> membersAdded,
            TurnContext turnContext) {
        
         //   return membersAdded.stream()
         //   .filter(
         //           member -> !StringUtils
         //                   .equals(member.getId(), turnContext.getActivity().getRecipient().getId()))
         //   .map(channel -> turnContext
         //           .sendActivity(
         //                   MessageFactory.text(this.WELCOME_MESSAGE + id)))
         //   .collect(CompletableFutures.toFutureList()).thenApply(resourceResponses -> null);
         

            return turnContext.sendActivity(
                MessageFactory.text("")).thenApply(sendResult -> null);
        }

        private boolean isErroneousRequest(String text) {
            //some times for unknown reasons, activity occurs with text: page_metadata, let's ignore these
            if(text.contains("page_metadata") || text.isEmpty())
            {
                return true;
            }
            return false;
        }

        private AzurePetStoreSessionInfo configureSession(TurnContext turnContext, String text) {
            // bot turn state and recipient not working with SoulMachines/DP (works in emulator) however this id appears to be unique per browser tab.
            // format is XKQtkRt4hDBdwzwP2bwhs-us|0000014, so we will hack off the dynamic ending piece
            String id = turnContext.getActivity().getId().trim();
            if(id.contains("-"))
            {
                id = id.substring(0, id.indexOf("-"));
            }

            AzurePetStoreSessionInfo azurePetStoreSessionInfo = this.sessionCache.get(id);

            // strip out session id and csrf token if one was passed in
            AzurePetStoreSessionInfo incomingAzurePetStoreSessionInfo = PetStoreAssistantUtilities
                    .getAzurePetStoreSessionInfo(text);
            if (incomingAzurePetStoreSessionInfo != null) {
                text = incomingAzurePetStoreSessionInfo.getNewText();
                //turnContext.getActivity().getId() is unique per browser over the broken recipient for some reason
                this.sessionCache.put(id, incomingAzurePetStoreSessionInfo);
                azurePetStoreSessionInfo = incomingAzurePetStoreSessionInfo;
                azurePetStoreSessionInfo.setId(id);
            }
            else
            {
                azurePetStoreSessionInfo.setNewText(text);
            }


            return azurePetStoreSessionInfo;
        }
   }
