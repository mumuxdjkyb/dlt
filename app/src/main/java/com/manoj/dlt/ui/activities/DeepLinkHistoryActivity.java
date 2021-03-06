package com.manoj.dlt.ui.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.getbase.floatingactionbutton.FloatingActionsMenu;
import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetSequence;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.manoj.dlt.Constants;
import com.manoj.dlt.DbConstants;
import com.manoj.dlt.R;
import com.manoj.dlt.events.DeepLinkFireEvent;
import com.manoj.dlt.features.DeepLinkHistoryFeature;
import com.manoj.dlt.features.ProfileFeature;
import com.manoj.dlt.models.DeepLinkInfo;
import com.manoj.dlt.models.ResultType;
import com.manoj.dlt.ui.ConfirmShortcutDialog;
import com.manoj.dlt.ui.adapters.DeepLinkListAdapter;
import com.manoj.dlt.utils.TextChangedListener;
import com.manoj.dlt.utils.Utilities;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import hotchemi.android.rate.AppRate;

public class DeepLinkHistoryActivity extends AppCompatActivity
{
    public static final String TAG_DIALOG = "dialog";
    private ListView _listView;
    private FloatingActionsMenu _fabMenu;
    private EditText _deepLinkInput;
    private DeepLinkListAdapter _adapter;
    private String _previousClipboardText;
    private ValueEventListener _historyUpdateListener;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deep_link_history);
        initView();
        _historyUpdateListener = getFirebaseHistoryListener();
    }

    private void initView()
    {
        _deepLinkInput = (EditText) findViewById(R.id.deep_link_input);
        _listView = (ListView) findViewById(R.id.deep_link_list_view);
        _adapter = new DeepLinkListAdapter(new ArrayList<DeepLinkInfo>(), this);
        configureDeepLinkInput();
        findViewById(R.id.deep_link_fire).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                extractAndFireLink();
            }
        });
        setFabMenuActions();
        setAppropriateLayout();
        configureListView();
    }

    private void setFabMenuActions()
    {
        setFabMenuOrientation();
        setFabListeners();
    }

    private void setFabListeners()
    {
        _fabMenu.findViewById(R.id.fab_web).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                if (Constants.isFirebaseAvailable(DeepLinkHistoryActivity.this))
                {
                    String userId = ProfileFeature.getInstance(DeepLinkHistoryActivity.this).getUserId();
                    Utilities.showAlert("Fire from your PC", "go to " + Constants.WEB_APP_LINK + userId, DeepLinkHistoryActivity.this);
                } else
                {
                    Utilities.raiseError(getString(R.string.play_services_error), DeepLinkHistoryActivity.this);
                }
            }
        });
        _fabMenu.findViewById(R.id.fab_share).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                Utilities.shareApp(DeepLinkHistoryActivity.this);
            }
        });
        _fabMenu.findViewById(R.id.fab_rate).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.GOOGLE_PLAY_URI)));
                //Do not show app rate dialog anymore
                AppRate.with(DeepLinkHistoryActivity.this).setAgreeShowDialog(false);
            }
        });
        _fabMenu.setOnFloatingActionsMenuUpdateListener(new FloatingActionsMenu.OnFloatingActionsMenuUpdateListener()
        {
            @Override
            public void onMenuExpanded()
            {
                setContentInFocus(true);
            }

            @Override
            public void onMenuCollapsed()
            {
                setContentInFocus(false);
            }
        });
    }

    private void setFabMenuOrientation()
    {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)
        {
            _fabMenu = (FloatingActionsMenu) findViewById(R.id.fab_menu_vertical);
        } else
        {
            _fabMenu = (FloatingActionsMenu) findViewById(R.id.fab_menu_horizontal);
        }
        _fabMenu.setVisibility(View.VISIBLE);
    }

    private void configureListView()
    {
        _listView.setAdapter(_adapter);
        _listView.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long l)
            {
                DeepLinkInfo info = (DeepLinkInfo) _adapter.getItem(position);
                setDeepLinkInputText(info.getDeepLink());
            }
        });
        _listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener()
        {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id)
            {
                showConfirmShortcutDialog((DeepLinkInfo) _adapter.getItem(position));
                return true;
            }
        });
    }

    private void showConfirmShortcutDialog(DeepLinkInfo info)
    {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("dialog");
        if (prev != null)
        {
            ft.remove(prev);
        }
        ft.addToBackStack(null);

        // Create and show the dialog.
        ConfirmShortcutDialog.newInstance(info.getDeepLink(), info.getActivityLabel()).show(ft, TAG_DIALOG);
    }

    private void configureDeepLinkInput()
    {
        _deepLinkInput.requestFocus();
        _deepLinkInput.setOnEditorActionListener(new TextView.OnEditorActionListener()
        {
            @Override
            public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent)
            {
                if (shouldFireDeepLink(actionId))
                {
                    extractAndFireLink();
                    return true;
                } else
                {
                    return false;
                }
            }
        });
        _deepLinkInput.addTextChangedListener(new TextChangedListener()
        {
            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2)
            {
                _fabMenu.collapse();
                _adapter.updateResults(charSequence);
            }
        });
        _deepLinkInput.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                _fabMenu.collapse();
            }
        });
    }

    private void pasteFromClipboard()
    {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (!Utilities.isProperUri(_deepLinkInput.getText().toString()) && clipboardManager.hasPrimaryClip())
        {
            ClipData.Item clipItem = clipboardManager.getPrimaryClip().getItemAt(0);
            if (clipItem != null)
            {
                if (clipItem.getText() != null)
                {
                    String clipBoardText = clipItem.getText().toString();
                    if (Utilities.isProperUri(clipBoardText) && !clipBoardText.equals(_previousClipboardText))
                    {
                        setDeepLinkInputText(clipBoardText);
                        _previousClipboardText = clipBoardText;
                    }
                } else if (clipItem.getUri() != null)
                {
                    String clipBoardText = clipItem.getUri().toString();
                    if (Utilities.isProperUri(clipBoardText) && !clipBoardText.equals(_previousClipboardText))
                    {
                        setDeepLinkInputText(clipBoardText);
                        _previousClipboardText = clipBoardText;
                    }
                }
            }
        }
    }

    private void setAppropriateLayout()
    {
        showDeepLinkRootView();

        if (Utilities.isAppTutorialSeen(this))
        {
            AppRate.showRateDialogIfMeetsConditions(this);
        } else
        {
            launchTutorial();
            Utilities.setAppTutorialSeen(DeepLinkHistoryActivity.this);
        }
    }

    private void showDeepLinkRootView()
    {
        findViewById(R.id.deep_link_history_root).setVisibility(View.VISIBLE);
        _deepLinkInput.requestFocus();
        Utilities.showKeyboard(this);
    }

    public void extractAndFireLink()
    {
        String deepLinkUri = _deepLinkInput.getText().toString();
        Utilities.checkAndFireDeepLink(deepLinkUri, this);
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        initListViewData();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        pasteFromClipboard();
    }

    @Override
    protected void onStop()
    {
        EventBus.getDefault().unregister(this);
        removeFirebaseListener();
        super.onStop();
    }

    @Subscribe (sticky = true, threadMode = ThreadMode.MAIN)
    public void onEvent(DeepLinkFireEvent deepLinkFireEvent)
    {
        String deepLinkString = deepLinkFireEvent.getDeepLinkInfo().getDeepLink();
        setDeepLinkInputText(deepLinkString);
        if (deepLinkFireEvent.getResultType().equals(ResultType.SUCCESS))
        {
            _adapter.updateResults(deepLinkString);
        } else
        {
            if (DeepLinkFireEvent.FAILURE_REASON.NO_ACTIVITY_FOUND.equals(deepLinkFireEvent.getFailureReason()))
            {
                Utilities.raiseError(getString(R.string.error_no_activity_resolved).concat(": ").concat(deepLinkString), this);
            } else if (DeepLinkFireEvent.FAILURE_REASON.IMPROPER_URI.equals(deepLinkFireEvent.getFailureReason()))
            {
                Utilities.raiseError(getString(R.string.error_improper_uri).concat(": ").concat(deepLinkString), this);
            }
        }
        EventBus.getDefault().removeStickyEvent(deepLinkFireEvent);
    }

    @Override
    public void onBackPressed()
    {
        if (_fabMenu.isExpanded())
        {
            _fabMenu.collapse();
        } else
        {
            super.onBackPressed();
        }
    }

    private void initListViewData()
    {
        if (Constants.isFirebaseAvailable(this))
        {
            //Attach callback to init adapter from data in firebase
            attachFirebaseListener();
        } else
        {
            List<DeepLinkInfo> deepLinkInfoList = DeepLinkHistoryFeature.getInstance(this).getLinkHistoryFromFileSystem();
            if (deepLinkInfoList.size() > 0)
            {
                showShortcutBannerIfNeeded();
            }
            _adapter.updateBaseData(deepLinkInfoList);
            findViewById(R.id.progress_wheel).setVisibility(View.GONE);
        }
        _adapter.updateResults(_deepLinkInput.getText().toString());
    }

    private void launchTutorial()
    {
        final DeepLinkInfo deepLinkInfo = new DeepLinkInfo("deeplinktester://example", "Deep Link Tester", getPackageName(), new Date().getTime());

        final View demoHeaderView = _adapter.createView(0, getLayoutInflater().inflate(R.layout.deep_link_info_layout, null, false), deepLinkInfo);
        demoHeaderView.setBackgroundColor(ResourcesCompat.getColor(getResources(), R.color.White, getTheme()));
        _listView.addHeaderView(demoHeaderView);

        new TapTargetSequence(this)
                .targets(TapTarget.forView(findViewById(R.id.deep_link_input), getString(R.string.onboarding_input_title))
                            .dimColor(android.R.color.black)
                            .outerCircleColor(R.color.SlateGray)
                            .targetCircleColor(R.color.fabColorNormal)
                            .tintTarget(false),
                        TapTarget.forView(findViewById(R.id.deep_link_fire), getString(R.string.onboarding_launch_title))
                            .dimColor(android.R.color.black)
                            .outerCircleColor(R.color.SlateGray)
                            .targetCircleColor(R.color.fabColorNormal)
                            .tintTarget(false),
                        TapTarget.forView(demoHeaderView, getString(R.string.onboarding_history_title))
                            .dimColor(android.R.color.black)
                            .outerCircleColor(R.color.SlateGray)
                            .targetCircleColor(R.color.fabColorNormal)
                            .tintTarget(false))
                .listener(new TapTargetSequence.Listener()
                {
                    @Override
                    public void onSequenceFinish()
                    {
                        _listView.removeHeaderView(demoHeaderView);
                    }

                    @Override
                    public void onSequenceCanceled(TapTarget lastTarget)
                    {
                        _listView.removeHeaderView(demoHeaderView);
                    }
                })
                .start();
    }

    private void attachFirebaseListener()
    {
        if (Constants.isFirebaseAvailable(this))
        {
            DatabaseReference baseUserReference = ProfileFeature.getInstance(this).getCurrentUserFirebaseBaseRef();
            DatabaseReference linkReference = baseUserReference.child(DbConstants.USER_HISTORY);
            linkReference.addValueEventListener(_historyUpdateListener);
        }
    }

    private void removeFirebaseListener()
    {
        if (Constants.isFirebaseAvailable(this))
        {
            DatabaseReference baseUserReference = ProfileFeature.getInstance(this).getCurrentUserFirebaseBaseRef();
            DatabaseReference linkReference = baseUserReference.child(DbConstants.USER_HISTORY);
            linkReference.removeEventListener(_historyUpdateListener);
        }
    }

    private ValueEventListener getFirebaseHistoryListener()
    {
        return new ValueEventListener()
        {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot)
            {
                findViewById(R.id.progress_wheel).setVisibility(View.GONE);
                List<DeepLinkInfo> deepLinkInfos = new ArrayList<DeepLinkInfo>();
                for (DataSnapshot child : dataSnapshot.getChildren())
                {
                    DeepLinkInfo info = Utilities.getLinkInfo(child);
                    deepLinkInfos.add(info);
                }
                Collections.sort(deepLinkInfos);
                _adapter.updateBaseData(deepLinkInfos);
                if (_deepLinkInput != null && _deepLinkInput.getText().length() > 0)
                {
                    _adapter.updateResults(_deepLinkInput.getText().toString());
                }
                if (deepLinkInfos.size() > 0)
                {
                    showShortcutBannerIfNeeded();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError)
            {

            }
        };
    }

    private void showShortcutBannerIfNeeded()
    {
        if (!Utilities.isShortcutHintSeen(this))
        {
            findViewById(R.id.shortcut_hint_banner).setVisibility(View.VISIBLE);
            findViewById(R.id.shortcut_hint_banner_cancel).setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    Utilities.setShortcutBannerSeen(DeepLinkHistoryActivity.this);
                    findViewById(R.id.shortcut_hint_banner).setVisibility(View.GONE);
                }
            });
        }
    }

    private boolean shouldFireDeepLink(int actionId)
    {
        if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_NEXT)
        {
            return true;
        }
        return false;
    }

    private void setDeepLinkInputText(String text)
    {
        _deepLinkInput.setText(text);
        _deepLinkInput.setSelection(text.length());
    }

    private void setContentInFocus(boolean hideFocus)
    {
        View overlay = findViewById(R.id.list_focus_overlay);
        if (hideFocus)
        {
            overlay.setVisibility(View.VISIBLE);
        } else
        {
            overlay.setVisibility(View.GONE);
        }
        overlay.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                _fabMenu.collapse();
            }
        });
    }

}
