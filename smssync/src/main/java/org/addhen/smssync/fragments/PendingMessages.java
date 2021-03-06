/*
 * Copyright (c) 2010 - 2015 Ushahidi Inc
 * All rights reserved
 * Contact: team@ushahidi.com
 * Website: http://www.ushahidi.com
 * GNU Lesser General Public License Usage
 * This file may be used under the terms of the GNU Lesser
 * General Public License version 3 as published by the Free Software
 * Foundation and appearing in the file LICENSE.LGPL included in the
 * packaging of this file. Please review the following information to
 * ensure the GNU Lesser General Public License version 3 requirements
 * will be met: http://www.gnu.org/licenses/lgpl.html.
 *
 * If you have questions regarding the use of this file, please contact
 * Ushahidi developers at team@ushahidi.com.
 */

package org.addhen.smssync.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import com.squareup.otto.Subscribe;

import org.addhen.smssync.App;
import org.addhen.smssync.R;
import org.addhen.smssync.SyncDate;
import org.addhen.smssync.UiThread;
import org.addhen.smssync.adapters.PendingMessagesAdapter;
import org.addhen.smssync.database.BaseDatabseHelper;
import org.addhen.smssync.listeners.PendingMessagesActionModeListener;
import org.addhen.smssync.messages.ProcessSms;
import org.addhen.smssync.models.Message;
import org.addhen.smssync.services.SyncPendingMessagesService;
import org.addhen.smssync.state.ReloadMessagesEvent;
import org.addhen.smssync.tasks.ProgressTask;
import org.addhen.smssync.tasks.SyncType;
import org.addhen.smssync.tasks.TaskCanceled;
import org.addhen.smssync.tasks.state.State;
import org.addhen.smssync.tasks.state.SyncPendingMessagesState;
import org.addhen.smssync.tasks.state.SyncState;
import org.addhen.smssync.util.ServicesConstants;
import org.addhen.smssync.util.Util;
import org.addhen.smssync.views.PendingMessagesView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;

public class PendingMessages
        extends
        BaseListFragment<PendingMessagesView, Message, PendingMessagesAdapter> implements
        android.view.View.OnClickListener {

    public static final int PENDING_MESSAGES_INTENT_FLAG = 4;
    private static final String STATE_CHECKED = "org.addhen.smssync.fragments.STATE_CHECKED";
    private Intent syncPendingMessagesServiceIntent;

    private LinkedHashSet<Integer> mSelectedItemsPositions;

    private PendingMessagesActionModeListener multichoiceActionModeListener;

    /**
     * This will refresh content of the listview aka the pending messages when smssync successfully
     * syncs pending messages.
     */
    private BroadcastReceiver failedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                int status = intent.getIntExtra("failed", 1);

                if (status == 0) {
                    refreshListView();
                }
            }
        }
    };

    public PendingMessages() {
        super(PendingMessagesView.class, PendingMessagesAdapter.class,
                R.layout.list_messages, R.menu.pending_messages_menu,
                android.R.id.list);
        log("PendingMessages()");
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        log("onActivityCreated()");
        super.onActivityCreated(savedInstanceState);

        // show notification
        if (prefs.serviceEnabled().get()) {
            Util.showNotification(getActivity());
        }
        multichoiceActionModeListener = new PendingMessagesActionModeListener(
                this, listView);
        listView.setItemsCanFocus(false);
        listView.setLongClickable(true);
        listView.setOnItemLongClickListener(multichoiceActionModeListener);

        if (savedInstanceState != null) {
            int position = savedInstanceState.getInt(STATE_CHECKED, -1);

            if (position > -1) {
                listView.setItemChecked(position, true);
            }
        }
        view.sync.setOnClickListener(this);
        App.bus.register(this);
        getActivity().registerReceiver(failedReceiver,
                new IntentFilter(ServicesConstants.FAILED_ACTION));
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        log("onSaveInstanceState()");
        super.onSaveInstanceState(state);
        state.putInt(STATE_CHECKED, listView.getCheckedItemPosition());
    }

    @Override
    public void onResume() {
        log("onResume()");
        super.onResume();
        idle();
        loadingTask();
    }

    @Override
    public void onStart() {
        log("onStart()");
        super.onStart();
    }

    @Override
    public void onPause() {
        log("onPause()");
        super.onPause();
        App.bus.unregister(this);
    }

    @Override
    public void onDestroy() {
        log("onDestroy()");
        super.onDestroy();
        getActivity().unregisterReceiver(failedReceiver);

    }

    private void idle() {

        view.details.setText(getLastSyncText(new SyncDate(prefs).getLastSyncedDate()));
        view.status.setText(R.string.idle);
        view.status
                .setTextColor(getActivity().getResources().getColor(R.color.status_idle));
    }

    @Override
    public void onClick(android.view.View v) {
        if (v == view.sync) {
            startSync();
        }
    }

    private void startSync() {
        // make sure service is enabled
        if (prefs.serviceEnabled().get()) {
            if (!SyncPendingMessagesService.isServiceWorking()) {
                log("Sync in action");
                initSync();
            } else {
                log("Sync canceled by the user");
                // Sync button will be restored on next status update.
                view.sync.setText(R.string.stopping);
                view.sync.setEnabled(false);
                App.bus.post(new TaskCanceled());
            }
        } else {
            toastLong(R.string.no_configured_url);
        }
    }

    private void initSync() {
        log("syncMessages messagesUuid: ");
        ArrayList<String> messagesUuids = new ArrayList<>();

        if (mSelectedItemsPositions != null && mSelectedItemsPositions.size() > 0) {
            for (Integer position : mSelectedItemsPositions) {
                messagesUuids.add(adapter.getItem(position).getUuid());
            }
        }

        syncPendingMessagesServiceIntent = new Intent(getActivity(),
                SyncPendingMessagesService.class);

        syncPendingMessagesServiceIntent.putStringArrayListExtra(
                ServicesConstants.MESSAGE_UUID, messagesUuids);
        syncPendingMessagesServiceIntent.putExtra(SyncType.EXTRA,
                SyncType.MANUAL.name());
        syncPendingMessagesServiceIntent.addFlags(PENDING_MESSAGES_INTENT_FLAG);
        getActivity().startService(syncPendingMessagesServiceIntent);

    }

    /**
     * The last time the sync item was done.
     */
    private String getLastSyncText(final long lastSync) {
        log("Last sync: " + lastSync);
        return getString(R.string.idle_details,
                lastSync > 0 ? DateFormat.getDateTimeInstance().format(new Date(lastSync))
                        : getString(R.string.never));

    }

    public boolean performAction(MenuItem item) {
        log("performAction()");

        if (item.getItemId() == R.id.context_delete) {
            // only initialize selected items positions if this action is taken
            mSelectedItemsPositions = multichoiceActionModeListener.getSelectedItemPositions();
            performDeleteById();
            return true;
        } else if (item.getItemId() == R.id.context_sync) {
            // Synchronize by ID
            // only initialize selected items positions if this action is taken
            mSelectedItemsPositions = multichoiceActionModeListener.getSelectedItemPositions();
            startSync();
            multichoiceActionModeListener.activeMode.finish();
            multichoiceActionModeListener.getSelectedItemPositions().clear();
            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        log("onOptionsItemSelected()");
        if (item.getItemId() == R.id.import_sms) {
            importAllSms();
        } else if (item.getItemId() == R.id.delete) {
            performDeleteAll();
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Delete all messages
     */
    private void performDeleteAll() {
        log("perofrmDeleteAll()");
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(getString(R.string.confirm_message))
                .setCancelable(false)
                .setNegativeButton(getString(R.string.confirm_no),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        })
                .setPositiveButton(getString(R.string.confirm_yes),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                // delete all messages
                                new DeleteTask(getActivity()).execute((String) null);

                            }
                        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    /**
     * Import all messages from the Android messaging inbox
     */
    private void importAllSms() {
        log("importAllSms()");

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(getString(R.string.confirm_sms_import))
                .setCancelable(false)
                .setNegativeButton(getString(R.string.cancel),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        })
                .setPositiveButton(getString(R.string.ok),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                ImportMessagesTask importMessagesTask = new ImportMessagesTask(
                                        getActivity());
                                importMessagesTask.execute();

                            }
                        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    /**
     * Delete message by it's id
     */
    public void performDeleteById() {

        log("performDeleteById()");
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(getString(R.string.confirm_message))
                .setCancelable(false)
                .setNegativeButton(getString(R.string.confirm_no),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {

                                dialog.cancel();
                            }
                        })
                .setPositiveButton(getString(R.string.confirm_yes),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                // Delete by ID
                                DeleteTask deleteTask = new DeleteTask(getActivity());
                                deleteTask.deletebyUuid = true;
                                deleteTask.execute((String) null);

                            }
                        });
        AlertDialog alert = builder.create();
        alert.show();

    }

    /**
     * Get messages from the database.
     *
     * @return void
     */
    public void showMessages() {
        loadingTask();
    }

    public void refreshListView() {
        loadingTask();
    }

    @Subscribe
    public void syncStateChanged(final SyncPendingMessagesState newState) {

        log("syncChanged:" + newState);
        if (view == null || newState.syncType.isBackground()) {
            return;
        }

        stateChanged(newState);

        switch (newState.state) {
            case FINISHED_SYNC:
                finishedSync(newState);
                break;
            case SYNC:
                log("In sync state " + " items to sync: " + newState.itemsToSync + " syncdItems "
                        + newState.currentSyncedItems + " faileditems: "
                        + newState.currentFailedItems);
                view.sync.setText(R.string.cancel);

                view.status.setText(R.string.working);
                view.details.setText(newState
                        .getNotification(getActivity().getResources()));
                view.progressStatus.setIndeterminate(false);
                view.progressStatus.setProgress(newState.currentProgress);
                view.progressStatus.setMax(newState.itemsToSync);
                break;
            case CANCELED_SYNC:
                view.status.setText(R.string.canceled);

                view.details.setText(getString(R.string.sync_canceled_details,
                        newState.currentSyncedItems,
                        newState.itemsToSync));
                break;

        }

    }

    private void finishedSync(SyncPendingMessagesState state) {
        int itemToSync = state.itemsToSync;
        String text = null;
        if (itemToSync > 0) {
            text = getActivity().getResources().getQuantityString(
                    R.plurals.sync_done_details, itemToSync,
                    itemToSync);
            log("Finished: successfull: " + state.currentSyncedItems + " failed: "
                    + state.currentFailedItems + " progress: " + state.currentProgress);
            text += getActivity().getResources().getString(R.string.sync_status_done,
                    state.currentSyncedItems,
                    state.currentFailedItems);

        } else if (itemToSync == 0) {
            text = getActivity().getString(R.string.empty_list);
        }
        view.status.setText(R.string.done);
        view.status.setTextColor(getActivity().getResources().getColor(R.color.status_done));
        view.details.setText(text);
        showMessages();
    }

    private void stateChanged(State state) {
        setViewAttributes(state.state);
        switch (state.state) {
            case INITIAL:
                idle();
                break;

        }
    }

    private void setViewAttributes(final SyncState state) {

        switch (state) {
            case SYNC:
                view.status
                        .setTextColor(getActivity().getResources().getColor(R.color.status_sync));

                break;
            case ERROR:
                view.progressStatus.setProgress(0);
                view.progressStatus.setIndeterminate(false);
                view.status.setTextColor(getActivity().getResources()
                        .getColor(R.color.status_error));

                setButtonsToDefault();
                break;
            default:
                view.progressStatus.setProgress(0);
                view.progressStatus.setIndeterminate(false);
                view.status
                        .setTextColor(getActivity().getResources().getColor(R.color.status_idle));
                setButtonsToDefault();
                break;
        }
    }

    private void setButtonsToDefault() {

        view.sync.setEnabled(true);
        view.sync.setText(R.string.sync);
    }

    public void loadingTask() {
        view.emptyView.setVisibility(android.view.View.GONE);
        fetchMessages();
    }

    private void fetchMessages() {
        App.getDatabaseInstance().getMessageInstance().fetchPending(new BaseDatabseHelper.DatabaseCallback<List<Message>>() {
            @Override
            public void onFinished(final List<Message> result) {
                if (result != null) {
                    UiThread.getInstance().post(new Runnable() {
                        @Override
                        public void run() {
                            view.listLoadingProgress.setVisibility(android.view.View.GONE);
                            view.emptyView.setVisibility(View.VISIBLE);
                            adapter.setItems(result);
                            listView.setAdapter(adapter);
                        }
                    });
                } else {
                    toastLong("No pending messages");
                }

            }

            @Override
            public void onError(Exception exception) {

            }
        });
    }

    @Subscribe
    public void reloadMessages(final ReloadMessagesEvent event) {
        loadingTask();
    }

    // Thread class to handle synchronous execution of message importation task.
    private class ImportMessagesTask extends ProgressTask {

        protected Boolean status;

        protected Context appContext;

        public ImportMessagesTask(Activity activity) {
            super(activity, R.string.please_wait);
            appContext = activity;
        }

        @Override
        protected Boolean doInBackground(String... args) {

            status = new ProcessSms(appContext).importMessages();

            return status;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (success) {
                if (status) {
                    fetchMessages();
                } else {
                    toastLong(R.string.nothing_to_import);
                }
            }
        }
    }

    protected class DeleteTask extends ProgressTask {

        protected boolean deletebyUuid = false;

        protected int deleted = 0;

        public DeleteTask(Activity activity) {
            super(activity);

        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            dialog.cancel();
            activity.setProgressBarIndeterminateVisibility(true);
        }

        @Override
        protected Boolean doInBackground(String... args) {

            if (adapter.getCount() == 0) {
                deleted = 1;
            } else {
                // delete by uuid is set
                if (deletebyUuid) {

                    for (Integer position : mSelectedItemsPositions) {
                        App.getDatabaseInstance().getMessageInstance().fetchByUuid(adapter.getItem(position).getUuid(), new BaseDatabseHelper.DatabaseCallback<Message>() {
                            @Override
                            public void onFinished(Message result) {
                                // Do nothing
                            }

                            @Override
                            public void onError(Exception exception) {
                                // Do nothing
                            }
                        });

                    }
                } else {
                    App.getDatabaseInstance().getMessageInstance().deleteAll(new BaseDatabseHelper.DatabaseCallback<Void>() {
                        @Override
                        public void onFinished(Void result) {
                            // Do nothing
                        }

                        @Override
                        public void onError(Exception exception) {
                            //Do nothing
                        }
                    });
                }
                deleted = 2;
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            activity.setProgressBarIndeterminateVisibility(false);
            view.emptyView.setVisibility(View.VISIBLE);
            if (success) {
                if (deleted == 1) {
                    toastLong(R.string.no_messages_to_delete);
                } else {

                    if (deleted == 2) {
                        toastLong(R.string.messages_deleted);

                    } else {
                        toastLong(R.string.messages_deleted_failed);
                    }
                }
                fetchMessages();
                if (multichoiceActionModeListener.activeMode != null) {
                    multichoiceActionModeListener.activeMode.finish();
                    multichoiceActionModeListener.getSelectedItemPositions().clear();
                }

            }
        }
    }

}
