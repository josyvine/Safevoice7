package com.safevoice.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.safevoice.app.models.Contact;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A Singleton class to manage CRUD (Create, Read, Update, Delete) operations
 * for emergency contacts. It persists the contacts using SharedPreferences by
 * converting Contact objects to and from JSON strings.
 */
public class ContactsManager {

    private static final String TAG = "ContactsManager";
    private static final String PREFS_NAME = "SafeVoiceContactsPrefs";
    private static final String KEY_PRIMARY_CONTACT = "primary_contact";
    private static final String KEY_PRIORITY_CONTACTS = "priority_contacts";

    private static ContactsManager instance;
    private final SharedPreferences sharedPreferences;

    // Private constructor to enforce the Singleton pattern.
    private ContactsManager(Context context) {
        sharedPreferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Gets the single instance of the ContactsManager.
     *
     * @param context The application context, needed to initialize SharedPreferences.
     * @return The singleton instance of ContactsManager.
     */
    public static synchronized ContactsManager getInstance(Context context) {
        if (instance == null) {
            instance = new ContactsManager(context);
        }
        return instance;
    }

    /**
     * Saves the primary contact. Overwrites any existing primary contact.
     *
     * @param contact The Contact object to be saved as the primary contact.
     */
    public void savePrimaryContact(Contact contact) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (contact != null) {
            JSONObject contactJson = contact.toJSONObject();
            if (contactJson != null) {
                editor.putString(KEY_PRIMARY_CONTACT, contactJson.toString());
            }
        } else {
            editor.remove(KEY_PRIMARY_CONTACT);
        }
        editor.apply();
    }

    /**
     * Retrieves the primary contact.
     *
     * @return The saved primary Contact object, or null if none is set.
     */
    public Contact getPrimaryContact() {
        String contactJsonString = sharedPreferences.getString(KEY_PRIMARY_CONTACT, null);
        if (contactJsonString != null) {
            try {
                JSONObject contactJson = new JSONObject(contactJsonString);
                return Contact.fromJSONObject(contactJson);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing primary contact JSON", e);
                return null;
            }
        }
        return null;
    }

    /**
     * Retrieves the list of all priority contacts.
     *
     * @return An ArrayList of Contact objects. Returns an empty list if none are saved.
     */
    public List<Contact> getPriorityContacts() {
        List<Contact> contacts = new ArrayList<>();
        String contactsJsonString = sharedPreferences.getString(KEY_PRIORITY_CONTACTS, null);
        if (contactsJsonString != null) {
            try {
                JSONArray contactsJsonArray = new JSONArray(contactsJsonString);
                for (int i = 0; i < contactsJsonArray.length(); i++) {
                    JSONObject contactJson = contactsJsonArray.getJSONObject(i);
                    Contact contact = Contact.fromJSONObject(contactJson);
                    if (contact != null) {
                        contacts.add(contact);
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing priority contacts JSON array", e);
            }
        }
        return contacts;
    }

    /**
     * Adds a new priority contact to the existing list.
     *
     * @param newContact The new Contact to add.
     */
    public void addPriorityContact(Contact newContact) {
        List<Contact> currentContacts = getPriorityContacts();
        currentContacts.add(newContact);
        savePriorityContactsList(currentContacts);
    }

    /**
     * Deletes a specific priority contact from the list.
     *
     * @param contactToDelete The Contact object to be removed.
     */
    public void deletePriorityContact(Contact contactToDelete) {
        List<Contact> currentContacts = getPriorityContacts();
        // The .equals() method in the Contact class is crucial for this to work correctly.
        currentContacts.remove(contactToDelete);
        savePriorityContactsList(currentContacts);
    }

    /**
     * Clears all contacts from local SharedPreferences.
     * Useful for switching accounts or executing clean logins.
     */
    public void clearAllContacts() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(KEY_PRIMARY_CONTACT);
        editor.remove(KEY_PRIORITY_CONTACTS);
        editor.apply();
        Log.d(TAG, "All local contacts cleared successfully from cache.");
    }
    
    /**
     * Saves the entire list of priority contacts.
     * Exposed as public to support programmatic restoration of contacts fetched from Firestore.
     *
     * @param contacts The list of Contact objects to save.
     */
    public void savePriorityContactsList(List<Contact> contacts) {
        JSONArray contactsJsonArray = new JSONArray();
        if (contacts != null) {
            for (Contact contact : contacts) {
                JSONObject contactJson = contact.toJSONObject();
                if (contactJson != null) {
                    contactsJsonArray.put(contactJson);
                }
            }
        }
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_PRIORITY_CONTACTS, contactsJsonArray.toString());
        editor.apply();
    }
}