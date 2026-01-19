package com.redisplay.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.redisplay.app.utils.ConfigManager;

public class SetupActivity extends Activity {

    private EditText urlInput;
    private Button saveButton;
    private ConfigManager configManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        configManager = new ConfigManager(this);
        
        // If URL already exists, pre-fill it
        String currentUrl = configManager.getServerUrl();
        
        urlInput = (EditText) findViewById(R.id.urlInput);
        saveButton = (Button) findViewById(R.id.saveButton);
        
        if (currentUrl != null && !currentUrl.isEmpty()) {
            urlInput.setText(currentUrl);
        }
        // No default URL - user must enter their server URL

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = urlInput.getText().toString().trim();
                
                if (url.isEmpty()) {
                    Toast.makeText(SetupActivity.this, "Please enter a server URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Save URL
                configManager.setServerUrl(url);
                
                // Go to Main Activity
                Intent intent = new Intent(SetupActivity.this, MainActivity.class);
                startActivity(intent);
                finish();
            }
        });
    }
}
